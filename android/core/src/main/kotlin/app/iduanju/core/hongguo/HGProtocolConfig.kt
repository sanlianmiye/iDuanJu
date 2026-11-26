package app.iduanju.core.hongguo

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hongguo playback protocol knobs that change without an app release (medusa field 21, request profile,
 * hosts, paths, baseline identity). The signing algorithm itself stays in code. The built-in value is the
 * default; a validated remote copy (`configs/hongguo-protocol.json` in the repo) overrides it when its
 * `configVersion` is not older than the built-in one. Mirrors STV/Providers/Hongguo/HGProtocolConfig.swift.
 */
data class HGProtocolConfig(
    val configVersion: Int,
    /** null = omit the field entirely. */
    val field21: Int?,
    val appQuery: List<Pair<String, String>>,
    val preloadHosts: List<String>,
    val preloadPath: String,
    val preloadDrScene: String,
    val preloadDeviceLevel: Int,
    val tabHosts: List<String>,
    val tabPath: String,
    val tabDeviceLevel: Int,
    val fallbackIdentity: HGOneShotDevice.Identity?,
    val preferFallbackIdentity: Boolean = true,
) {
    companion object {
        const val SCHEMA = 1
        private const val MAX_BYTES = 64 * 1024
        private val QUERY_KEYS = setOf(
            "aid", "app_name", "version_code", "version_name", "update_version_code", "manifest_version_code",
            "os", "ssmix", "channel", "device_platform", "device_type", "device_brand", "os_version", "os_api",
            "ac", "language", "host_abi",
        )
        private val HOST = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)*")
        private val PATH = Regex("/?[A-Za-z0-9_]+(/[A-Za-z0-9_]+){1,6}/?")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        val builtin = HGProtocolConfig(
            configVersion = 2,
            field21 = 312,
            appQuery = listOf(
                "aid" to "8662", "app_name" to "novelread", "version_code" to "74532", "version_name" to "7.4.5.32",
                "update_version_code" to "74532", "manifest_version_code" to "74532", "os" to "android", "ssmix" to "a",
                "channel" to "update_64", "device_platform" to "android", "device_type" to "25053RT47C",
                "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36", "ac" to "wifi", "language" to "zh",
                "host_abi" to "arm64-v8a",
            ),
            preloadHosts = listOf("reading.snssdk.com", "api5-normal-sinfonlinea.fqnovel.com", "api5-normal-sinfonlineb.fqnovel.com"),
            preloadPath = "/novel/player/multi_video_model/preload/v1",
            preloadDrScene = "preload",
            preloadDeviceLevel = 2,
            tabHosts = listOf("api5-normal-sinfonlineb.fqnovel.com", "api5-normal-hl.fqnovel.com"),
            tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/",
            tabDeviceLevel = 1,
            fallbackIdentity = HGOneShotDevice.Identity(
                "1624456208323876", "1624456208327972", "91d56f7e-c027-4ce9-9a3c-229d59a13c3b", "56a962410c494bba", 0L,
            ),
        )

        private fun hosts(a: JSONArray?): List<String>? {
            if (a == null || a.length() !in 1..6) return null
            val out = ArrayList<String>()
            for (i in 0 until a.length()) {
                val h = a.optString(i, "").lowercase()
                if (!HOST.matches(h) || !(h == "fqnovel.com" || h.endsWith(".fqnovel.com") || h == "snssdk.com" || h.endsWith(".snssdk.com"))) return null
                out += h
            }
            return out
        }

        private fun path(s: String): String? = if (PATH.matches(s)) (if (s.startsWith("/")) s else "/$s") else null

        /** Returns null for anything malformed, oversized or pointing outside the allowed hosts. */
        fun parse(text: String): HGProtocolConfig? {
            return try {
            if (text.length > MAX_BYTES) return null
            val j = JSONObject(text)
            if (j.optInt("schema", -1) != SCHEMA) return null
            val version = j.optInt("configVersion", 0)
            if (version < 1) return null
            val medusa = j.optJSONObject("medusa") ?: return null
            if (!medusa.has("field21")) return null
            val f21: Int? = if (medusa.isNull("field21")) null else medusa.getInt("field21").also { if (it !in 0..65535) return null }
            val q = j.optJSONArray("appQuery") ?: return null
            if (q.length() !in 8..40) return null
            val query = ArrayList<Pair<String, String>>()
            for (i in 0 until q.length()) {
                val s = q.optString(i, "")
                val k = s.substringBefore('=', ""); val v = s.substringAfter('=', "\u0000")
                if (k !in QUERY_KEYS || v == "\u0000" || v.length > 64 || query.any { it.first == k }) return null
                query += k to v
            }
            if (listOf("aid", "version_code", "version_name").any { k -> query.none { it.first == k } }) return null
            val pre = j.optJSONObject("preload") ?: return null
            val tab = j.optJSONObject("tab") ?: return null
            val dr = pre.optString("drScene", "")
            if (dr != "preload" && dr != "player") return null
            val pl = pre.optInt("deviceLevel", -1); val tl = tab.optInt("deviceLevel", -1)
            if (pl !in 0..5 || tl !in 0..5) return null
            val fb = j.optJSONObject("fallbackIdentity")?.let { o ->
                val did = o.optString("did"); val iid = o.optString("iid"); val cdid = o.optString("cdid"); val ou = o.optString("openudid")
                if (!(did.matches(Regex("[1-9][0-9]{9,23}")) && iid.matches(Regex("[1-9][0-9]{9,23}")) && UUID.matches(cdid) && ou.matches(Regex("[0-9a-f]{16}")))) return null
                HGOneShotDevice.Identity(did, iid, cdid, ou, 0L)
            }
            HGProtocolConfig(
                version, f21, query, hosts(pre.optJSONArray("hosts")) ?: return null, path(pre.optString("path", "")) ?: return null, dr, pl,
                hosts(tab.optJSONArray("hosts")) ?: return null, path(tab.optString("path", "")) ?: return null, tl, fb,
                j.optBoolean("preferFallbackIdentity", true),
            )
        } catch (_: Exception) { null }
        }
    }
}

/** Holder + loader: disk cache first, then a throttled background refresh from the repo (raw + CDN mirrors). */
object HGProtocol {
    @Volatile var current: HGProtocolConfig = HGProtocolConfig.builtin
        internal set
    private const val FILE = "hongguo_protocol.json"
    private const val STALE_MS = 30 * 60_000L
    private const val FORCE_MIN_MS = 60_000L
    private val URLS = listOf(
        "https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/main/configs/hongguo-protocol.json",
        "https://cdn.jsdelivr.net/gh/my-name-is-alan/iDuanJu@main/configs/hongguo-protocol.json",
        "https://fastly.jsdelivr.net/gh/my-name-is-alan/iDuanJu@main/configs/hongguo-protocol.json",
    )
    @Volatile private var lastAttempt = 0L
    @Volatile private var inFlight = false

    fun load(dir: File) {
        val parsed = try { File(dir, FILE).takeIf { it.exists() }?.readText()?.let { HGProtocolConfig.parse(it) } } catch (_: Exception) { null }
        current = if (parsed != null && parsed.configVersion >= HGProtocolConfig.builtin.configVersion) parsed else HGProtocolConfig.builtin
    }

    /** Blocking. Keeps the highest valid `configVersion`; never downgrades below the built-in one. */
    fun refresh(http: OkHttpClient, dir: File): Boolean {
        val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        for (u in URLS) {
            val text = try {
                client.newCall(Request.Builder().url(u).header("Cache-Control", "no-cache").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (_: Exception) { null } ?: continue
            val cfg = HGProtocolConfig.parse(text) ?: continue
            if (cfg.configVersion < current.configVersion) return false
            current = cfg
            try { dir.mkdirs(); File(dir, FILE).writeText(text) } catch (_: Exception) {}
            return true
        }
        return false
    }

    fun refreshAsync(http: OkHttpClient, dir: File, force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (inFlight || now - lastAttempt < (if (force) FORCE_MIN_MS else STALE_MS)) return
            inFlight = true; lastAttempt = now
        }
        Thread({ try { refresh(http, dir) } finally { inFlight = false } }, "hg-protocol").apply { isDaemon = true }.start()
    }
}
