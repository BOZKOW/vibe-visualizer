package io.github.bozkow.vibevisualizer.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One answer, read in full (bodies are small: JSON, lyrics, short pages). */
class Resp(val code: Int, val body: ByteArray, val headers: okhttp3.Headers, val ms: Long) {
    val ok get() = code in 200..299
    val text: String by lazy { body.toString(Charsets.UTF_8) }
    fun json(): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
    fun jsonArray(): JSONArray? = runCatching { JSONArray(text) }.getOrNull()
    fun header(n: String): String? = headers[n]
}

/** A request that never reached the other side (offline, blocked, timed out). */
class NetError(msg: String, cause: Throwable? = null) : IOException(msg, cause)

data class NetStat(val ms: Long?, val status: Int?, val at: Long)

object Net {
    lateinit var http: OkHttpClient
        private set
    private lateinit var cm: ConnectivityManager
    val stats = mutableStateMapOf<String, NetStat>()     // ping meter: service -> its last request
    val online = mutableStateOf(true)
    val vpn = mutableStateOf(false)                      // Tailscale (or another VPN) is on
    val privateDns = mutableStateOf(false)               // Android's Private DNS is on (it hides MagicDNS names)
    val JSON_TYPE = "application/json".toMediaType()

    fun init(ctx: Context) {
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
            .cache(Cache(File(ctx.cacheDir, "http"), 30L * 1024 * 1024))
            .build()
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        refreshNet()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNet()
            override fun onLost(network: Network) = refreshNet()
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = refreshNet()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refreshNet()
        })
    }

    private fun refreshNet() {
        val n = cm.activeNetwork
        val nc = n?.let { cm.getNetworkCapabilities(it) }
        val lp = n?.let { cm.getLinkProperties(it) }
        online.value = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        vpn.value = nc?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        privateDns.value = lp?.isPrivateDnsActive == true && lp.privateDnsServerName != null
    }

    fun mark(name: String, ms: Long?, status: Int?) { stats[name] = NetStat(ms, status, System.currentTimeMillis()) }

    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, _, _ -> response.close() } }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    /** A request, timed for the ping meter under [name] (when given). Throws [NetError] when nothing came back. */
    suspend fun fetch(
        url: String, name: String? = null, method: String = "GET", headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null, contentType: String? = null, noCache: Boolean = false,
    ): Resp {
        val t0 = SystemClock.elapsedRealtime()
        val rb = when {
            body != null -> body.toRequestBody((contentType ?: "application/octet-stream").toMediaType())
            method == "PUT" || method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val req = Request.Builder().url(url).method(method, rb).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("User-Agent", "VibeVisualizer-Android")
            if (noCache) header("Cache-Control", "no-cache")
        }.build()
        try {
            http.newCall(req).await().use { r ->
                val bytes = r.body.bytes()
                val ms = SystemClock.elapsedRealtime() - t0
                if (name != null) mark(name, ms, r.code)
                return Resp(r.code, bytes, r.headers, ms)
            }
        } catch (e: IOException) {
            if (name != null) mark(name, null, null)
            throw NetError(e.message ?: e.javaClass.simpleName, e)
        } catch (e: IllegalArgumentException) {
            throw NetError("not a valid address: $url", e)
        }
    }

    suspend fun getJson(url: String, name: String? = null): JSONObject {
        val r = fetch(url, name)
        if (!r.ok) throw NetError("HTTP ${r.code}")
        return r.json() ?: throw NetError("not JSON")
    }
}

/** Your Vibe Visualizer server (its Tailscale HTTPS address), or null when none is set. */
object Server {
    val base: String? get() = Vibe.cfg.server.takeIf { it.isNotEmpty() }
    fun url(path: String): String? = base?.let { it + path }
    val set get() = base != null
}
