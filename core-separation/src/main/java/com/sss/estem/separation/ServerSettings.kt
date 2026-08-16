package com.sss.estem.separation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the separation server lives.
 *
 * It is a setting rather than a constant because the server moves — it is whichever machine is
 * switched on, and its address changes with the network it joined. [DEFAULT_URL] is only a
 * starting value.
 */
class ServerSettings(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(preferences.getString(KEY_URL, DEFAULT_URL).orEmpty())

    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun set(url: String) {
        val cleaned = url.trim()
        preferences.edit().putString(KEY_URL, cleaned).apply()
        _baseUrl.value = cleaned
    }

    /** The current value as something [java.net.URL] will accept, or null if it is unusable. */
    fun resolved(): String? = normalise(_baseUrl.value)

    companion object {
        private const val PREFS = "separation"
        private const val KEY_URL = "serverUrl"

        /**
         * Empty on purpose. There is no address that is right for a fresh install: the server is
         * whichever machine on your own network is running it. Shipping a plausible-looking default
         * would be worse than none, because a wrong-but-reachable host on the same subnet would be
         * sent decoded audio before anyone noticed. Unset fails immediately and says so instead.
         */
        const val DEFAULT_URL = ""

        /**
         * Accepts what someone actually types: a bare host, a host and port, with or without a
         * scheme or a trailing slash. Anything left without a host is rejected rather than turned
         * into a URL that fails later with a worse message.
         */
        fun normalise(raw: String): String? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            if (!value.contains("://")) value = "http://$value"

            val scheme = value.substringBefore("://")
            val rest = value.substringAfter("://").trimEnd('/')
            if (scheme.isBlank() || rest.isEmpty()) return null

            // A port or a path with nothing in front of it is a typo, not an address.
            val host = rest.substringBefore('/').substringBefore(':')
            if (host.isEmpty()) return null

            return "$scheme://$rest"
        }
    }
}
