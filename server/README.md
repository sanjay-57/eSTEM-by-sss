# eSTEM separation server

htdemucs on a machine that can actually hold it, reachable from the phone over Wi-Fi.

## Why this exists

The on-device attempt failed for a reason that could not be worked around. The `htdemucs-onnx`
export has a **statically shaped input** — `(1, 2, 343980)`, 7.8 seconds, baked into the graph — so
the segment length cannot be reduced, and one segment needs several gigabytes. On a 7.6 GB phone
the process reached ~3.9 GB resident and was killed by the OOM killer partway through every track;
WorkManager then restarted it, which is why progress appeared stuck at 2–8% forever. The same
export is also 24,917 nodes, most of it shape plumbing (11,684 `Constant`, 2,968 `Shape`, 684
`ScatterND`), so it was never going to be quick either.

Measured here, on an M-series Mac over MPS:

| Track | Audio | Separation |
|---|---|---|
| Hell of a Night | 3:14 | 13.8 s |
| ALL THE LOVE | 3:49 | 16.2 s |

Roughly 14× realtime, against a phone that never finished a single track.

## Running it

```sh
cd server
python3 -m venv .venv                          # first time only
./.venv/bin/pip install -r requirements.txt    # first time only, ~2 GB of torch
./.venv/bin/python estem_server.py
```

It prints the address to type into the app:

```
[14:03:51] model ready · sources ['drums', 'bass', 'other', 'vocals'] · bag of 1
[14:03:51] listening on 0.0.0.0:8765
[14:03:51]   reachable at http://192.168.1.20:8765
```

The model (~80 MB) downloads itself on first run into `~/.cache/torch`. `ESTEM_PORT` and
`ESTEM_MODEL` override the port and the demucs model — `htdemucs_ft` is slower and slightly
better, `htdemucs_6s` adds guitar and piano but the app only has four arms.

**The phone and the machine must be on the same network**, and macOS may ask to allow incoming
connections the first time. Put the printed address into the app's Queue page under *Separation
engine → Separation server*, then *Save & test*: it should read back
`Connected · htdemucs on mps · 44100 Hz`.

To move the server to another machine, run the same thing there and change the address in the app.
Nothing else knows where it lives.

## Protocol

```
GET    /health                    -> {"ok","model","device","sampleRate","stems"}
POST   /separate                  -> 202 {"job"}
       X-Sample-Rate, X-Channels, body = raw s16le interleaved PCM
GET    /jobs/<id>                 -> {"state","progress","sampleRate","channels","frames","stems","error"}
GET    /jobs/<id>/stems/<name>    -> raw s16le PCM, 44100 Hz, stereo
DELETE /jobs/<id>
```

Two decisions worth keeping:

**PCM crosses the wire, not audio files.** The phone already decodes with MediaCodec for its own
stem cache, so it sends the decoded result. That removes every format question from the server —
no ffmpeg, no container parsing, and no codec that decodes differently at each end. The reply is
the same format, so a downloaded stem is written into the phone's cache byte for byte.

**Stems are addressed by name, not by index.** demucs emits `drums, bass, other, vocals`; the app's
own order is `vocals, drums, bass, melody`. Mapping those by position is a mistake with no symptom
other than the wrong slider moving the wrong instrument. `Stem.fileName` already matches the names
above, so the app looks stems up instead of counting them.

One job runs at a time — separation is compute-bound, and two at once is slower than two in a row.
Jobs live in `server/jobs/` and are deleted by the phone when it has collected them; anything left
behind is cleared on the next start.

## Security

There is none. It binds `0.0.0.0` with no authentication, and the app talks to it over cleartext
HTTP because a certificate for `192.168.x.x` is not something you can obtain. That is fine on a
home network and is not fine anywhere else — **do not port-forward this.** Anyone who can reach
the port can post audio to it, read back any job still on disk, and delete jobs.

`server/jobs/` holds the decoded PCM of whatever has been separated — your music, in the clear. It
is gitignored for that reason, and cleared on start. Nothing in it belongs in a commit.
