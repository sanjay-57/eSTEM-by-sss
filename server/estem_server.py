"""eSTEM separation server.

Runs htdemucs on a real machine and hands the phone back exactly the bytes it stores.

The phone already decodes audio with MediaCodec, so it uploads **headerless interleaved 16-bit
PCM** rather than the original file. That removes every format question from this side: no ffmpeg,
no container parsing, no codec that decodes differently here than it did there. The reply is the
same format, so the app writes the response body straight into its stem cache with no conversion.

Wire protocol
-------------
    GET    /health                     -> {"ok", "model", "device", "sampleRate"}
    POST   /separate                   -> 202 {"job"}
           X-Sample-Rate: 44100        (required) rate of the body
           X-Channels: 2               (required) 1 or 2
           X-Title: ...                (optional) for the log only
           body: raw s16le PCM, interleaved
    GET    /jobs/<id>                  -> {"state", "progress", "sampleRate", "channels",
                                           "frames", "stems", "error"}
    GET    /jobs/<id>/stems/<name>     -> raw s16le PCM at 44100 Hz, stereo
    DELETE /jobs/<id>                  -> {"ok": true}

Stems are addressed **by name** — drums, bass, other, vocals. The on-device version had to map
demucs' output order onto the app's own stem order by index, which is a mistake with no symptom
other than the wrong slider moving. Naming them on the wire removes that class of bug.

One job runs at a time. Separation is compute-bound and two at once is slower than two in a row.
"""

from __future__ import annotations

import contextlib
import inspect
import json
import os
import queue
import shutil
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Must be set before torch loads. htdemucs uses a few ops Metal has no kernel for; without this
# the run dies on the first of them instead of running that one op on the CPU.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

import numpy as np  # noqa: E402
import torch  # noqa: E402

import demucs.apply  # noqa: E402
from demucs.apply import BagOfModels, apply_model  # noqa: E402
from demucs.pretrained import get_model  # noqa: E402

MODEL_NAME = os.environ.get("ESTEM_MODEL", "htdemucs")
PORT = int(os.environ.get("ESTEM_PORT", "8765"))
JOBS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jobs")

# htdemucs is trained at 44.1 kHz and the app stores stems at whatever it is told, so this is the
# one rate that crosses the wire in either direction.
TARGET_RATE = 44100
STEMS = ("drums", "bass", "other", "vocals")

# 20 minutes of 48 kHz stereo. A ceiling here is what stops a malformed Content-Length from asking
# this process to allocate the machine.
MAX_UPLOAD_BYTES = 48_000 * 2 * 2 * 60 * 20


def log(*parts: object) -> None:
    print(f"[{time.strftime('%H:%M:%S')}]", *parts, flush=True)


@dataclass
class Job:
    id: str
    sample_rate: int
    channels: int
    frames: int
    title: str
    state: str = "queued"
    progress: float = 0.0
    error: str | None = None
    out_frames: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def directory(self) -> str:
        return os.path.join(JOBS_DIR, self.id)

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "state": self.state,
                "progress": round(self.progress, 4),
                "sampleRate": TARGET_RATE,
                "channels": 2,
                "frames": self.out_frames,
                "stems": list(STEMS) if self.state == "done" else [],
                "error": self.error,
            }

    def set(self, **values: object) -> None:
        with self.lock:
            for key, value in values.items():
                setattr(self, key, value)


class ProgressTqdm:
    """Stands in for the `tqdm` module inside `demucs.apply`.

    demucs 4.0.1 exposes no progress callback — with `progress=True` it simply wraps its list of
    segment futures in `tqdm.tqdm`. Substituting the module is therefore the only hook that does
    not mean reimplementing the segmenting, and reimplementing the segmenting is exactly the
    overlap-add bookkeeping worth not owning twice.

    A bag runs `apply_model` once per sub-model, so one pass through the futures is one sub-model's
    worth of the bar. htdemucs is a bag of one; htdemucs_ft is a bag of four.
    """

    def __init__(self, report, models: int) -> None:
        self._report = report
        self._models = max(1, models)
        self._done = 0

    def tqdm(self, iterable, **_):
        items = list(iterable)
        total = len(items) or 1
        for index, item in enumerate(items):
            yield item
            self._report((self._done + (index + 1) / total) / self._models)
        self._done += 1


@contextlib.contextmanager
def progress_through_tqdm(report, models: int):
    original = demucs.apply.tqdm
    demucs.apply.tqdm = ProgressTqdm(report, models)
    try:
        yield
    finally:
        demucs.apply.tqdm = original


class Separator:
    """Owns the model and the single worker thread that uses it."""

    def __init__(self) -> None:
        self.device = pick_device()
        log(f"loading {MODEL_NAME} on {self.device}")
        self.model = get_model(MODEL_NAME)
        self.model.to(self.device)
        self.model.eval()
        self.bag_size = len(self.model.models) if isinstance(self.model, BagOfModels) else 1
        log(f"model ready · sources {self.model.sources} · bag of {self.bag_size}")

        # apply_model has gained and lost keyword arguments across releases; ask this one what it
        # takes rather than assuming, so a version bump degrades instead of raising a TypeError
        # halfway through a job.
        self._apply_params = set(inspect.signature(apply_model).parameters)

        self.jobs: dict[str, Job] = {}
        self.queue: queue.Queue[Job] = queue.Queue()
        threading.Thread(target=self._work, daemon=True).start()

    def submit(self, job: Job) -> None:
        self.jobs[job.id] = job
        self.queue.put(job)

    def _work(self) -> None:
        while True:
            job = self.queue.get()
            try:
                self._separate(job)
            except Exception as error:  # noqa: BLE001 - the job must carry the failure, not die
                log(f"job {job.id} failed: {error!r}")
                job.set(state="failed", error=str(error))
                shutil.rmtree(job.directory(), ignore_errors=True)

    def _separate(self, job: Job) -> None:
        job.set(state="running", progress=0.0)
        started = time.time()
        directory = job.directory()

        raw = np.fromfile(os.path.join(directory, "source.pcm"), dtype="<i2")
        wav = torch.from_numpy(raw.astype(np.float32) / 32768.0)
        wav = wav.reshape(-1, job.channels).t().contiguous()

        if job.channels == 1:
            wav = wav.repeat(2, 1)

        if job.sample_rate != TARGET_RATE:
            import torchaudio

            log(f"job {job.id} resampling {job.sample_rate} -> {TARGET_RATE}")
            wav = torchaudio.functional.resample(wav, job.sample_rate, TARGET_RATE)

        # demucs is trained on loudness-normalised input and its own separate.py does this before
        # every call. Skipping it costs real separation quality on quiet or hot masters.
        reference = wav.mean(0)
        mean, std = reference.mean(), reference.std()
        wav = (wav - mean) / (std + 1e-8)

        sources = self._run(job, wav)
        sources = sources * std + mean

        order = list(self.model.sources)
        frames = sources.shape[-1]
        for name in STEMS:
            stem = sources[order.index(name)].cpu()
            data = (stem.clamp(-1.0, 1.0) * 32767.0).round().to(torch.int16)
            # (2, N) -> interleaved (N, 2), which is what the phone memory-maps.
            data.t().contiguous().numpy().astype("<i2").tofile(
                os.path.join(directory, f"{name}.pcm")
            )

        os.remove(os.path.join(directory, "source.pcm"))
        job.set(state="done", progress=1.0, out_frames=int(frames))
        log(
            f"job {job.id} done · {frames / TARGET_RATE:.1f}s of audio "
            f"in {time.time() - started:.1f}s"
        )

    def _run(self, job: Job, wav: torch.Tensor) -> torch.Tensor:
        """Runs the model, falling back to the CPU if the accelerator refuses the graph."""
        kwargs: dict[str, object] = {"progress": True}
        if "num_workers" in self._apply_params:
            kwargs["num_workers"] = 0

        def report(fraction: float) -> None:
            job.set(progress=min(0.99, fraction))

        for device in (self.device, "cpu"):
            try:
                with torch.no_grad(), progress_through_tqdm(report, self.bag_size):
                    return apply_model(self.model, wav[None], device=device, **kwargs)[0]
            except (RuntimeError, NotImplementedError) as error:
                if device == "cpu":
                    raise
                log(f"job {job.id} failed on {device} ({error}); retrying on cpu")
                self.device = "cpu"
                self.model.to("cpu")
                job.set(progress=0.0)
        raise RuntimeError("unreachable")


def pick_device() -> str:
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    separator: Separator

    def log_message(self, fmt: str, *args: object) -> None:
        pass  # the job log is the interesting one

    # ---- responses ----

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status: int, reason: str) -> None:
        self._json(status, {"error": reason})

    # ---- routes ----

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler's naming
        parts = [p for p in self.path.split("?")[0].split("/") if p]

        if parts == ["health"]:
            self._json(
                200,
                {
                    "ok": True,
                    "model": MODEL_NAME,
                    "device": self.separator.device,
                    "sampleRate": TARGET_RATE,
                    "stems": list(STEMS),
                },
            )
            return

        if len(parts) == 2 and parts[0] == "jobs":
            job = self.separator.jobs.get(parts[1])
            if job is None:
                self._error(404, "no such job")
                return
            self._json(200, job.snapshot())
            return

        if len(parts) == 4 and parts[0] == "jobs" and parts[2] == "stems":
            self._send_stem(parts[1], parts[3])
            return

        self._error(404, "not found")

    def _send_stem(self, job_id: str, name: str) -> None:
        job = self.separator.jobs.get(job_id)
        if job is None or name not in STEMS:
            self._error(404, "no such stem")
            return
        if job.state != "done":
            self._error(409, f"job is {job.state}")
            return

        path = os.path.join(job.directory(), f"{name}.pcm")
        if not os.path.isfile(path):
            self._error(404, "stem missing")
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(os.path.getsize(path)))
        self.end_headers()
        with open(path, "rb") as handle:
            shutil.copyfileobj(handle, self.wfile, length=1 << 20)

    def do_POST(self) -> None:  # noqa: N802
        if self.path.split("?")[0].rstrip("/") != "/separate":
            self._error(404, "not found")
            return

        try:
            sample_rate = int(self.headers["X-Sample-Rate"])
            channels = int(self.headers["X-Channels"])
            length = int(self.headers["Content-Length"])
        except (TypeError, ValueError):
            self._error(400, "X-Sample-Rate, X-Channels and Content-Length are required")
            return

        if channels not in (1, 2):
            self._error(400, "X-Channels must be 1 or 2")
            return
        if not 8000 <= sample_rate <= 192000:
            self._error(400, "implausible sample rate")
            return
        if length <= 0 or length > MAX_UPLOAD_BYTES:
            self._error(413, f"body must be 1..{MAX_UPLOAD_BYTES} bytes")
            return

        job = Job(
            id=uuid.uuid4().hex[:12],
            sample_rate=sample_rate,
            channels=channels,
            frames=length // (channels * 2),
            title=self.headers.get("X-Title", "untitled"),
        )
        os.makedirs(job.directory(), exist_ok=True)

        remaining = length
        with open(os.path.join(job.directory(), "source.pcm"), "wb") as handle:
            while remaining > 0:
                chunk = self.rfile.read(min(1 << 20, remaining))
                if not chunk:
                    break
                handle.write(chunk)
                remaining -= len(chunk)

        if remaining > 0:
            shutil.rmtree(job.directory(), ignore_errors=True)
            self._error(400, "upload truncated")
            return

        log(
            f"job {job.id} queued · {job.title} · {job.frames / sample_rate:.1f}s "
            f"@ {sample_rate} Hz x{channels}"
        )
        self.separator.submit(job)
        self._json(202, {"job": job.id})

    def do_DELETE(self) -> None:  # noqa: N802
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if len(parts) != 2 or parts[0] != "jobs":
            self._error(404, "not found")
            return
        job = self.separator.jobs.pop(parts[1], None)
        if job is not None:
            shutil.rmtree(job.directory(), ignore_errors=True)
        self._json(200, {"ok": True})


def main() -> int:
    shutil.rmtree(JOBS_DIR, ignore_errors=True)
    os.makedirs(JOBS_DIR, exist_ok=True)

    Handler.separator = Separator()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log(f"listening on 0.0.0.0:{PORT}")
    for address in local_addresses():
        log(f"  reachable at http://{address}:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("stopping")
    return 0


def local_addresses() -> list[str]:
    import socket

    found = []
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        address = info[4][0]
        if address not in found and not address.startswith("127."):
            found.append(address)
    return found


if __name__ == "__main__":
    sys.exit(main())
