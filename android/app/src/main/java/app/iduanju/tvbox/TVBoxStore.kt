package app.iduanju.tvbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.iduanju.core.cms.CMSCategory
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.cms.TVBoxImport
import app.iduanju.core.util.NetGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class TVBoxLiveChannel(
    val name: String,
    val group: String,
    val urls: List<String>,
    val logo: String = "",
) {
    fun toJSON(): JSONObject = JSONObject()
        .put("name", name)
        .put("group", group)
        .put("urls", JSONArray(urls))
        .put("logo", logo)

    companion object {
        fun fromJSON(o: JSONObject): TVBoxLiveChannel {
            val uArr = o.optJSONArray("urls") ?: JSONArray()
            val urls = (0 until uArr.length()).mapNotNull { uArr.optString(it).takeIf { s -> s.isNotBlank() } }
            return TVBoxLiveChannel(
                name = o.optString("name"),
                group = o.optString("group", "全部").ifBlank { "全部" },
                urls = urls,
                logo = o.optString("logo"),
            )
        }
    }
}

data class TVBoxRepoLive(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val channels: List<TVBoxLiveChannel> = emptyList(),
) {
    fun toJSON(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("enabled", enabled)
        .put("channels", JSONArray(channels.map { it.toJSON() }))

    companion object {
        fun fromJSON(o: JSONObject): TVBoxRepoLive {
            val arr = o.optJSONArray("channels") ?: JSONArray()
            val chs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(TVBoxLiveChannel::fromJSON) }
            return TVBoxRepoLive(
                id = o.optString("id").ifBlank { TVBoxStore.hashId(o.optString("url")) },
                name = o.optString("name", "直播源"),
                url = o.optString("url"),
                enabled = o.optBoolean("enabled", true),
                channels = chs,
            )
        }
    }
}

data class TVBoxRepoSite(
    val key: String,
    val name: String,
    val api: String,
    val adult: Boolean = false,
    val enabled: Boolean = true,
    val categories: List<CMSCategory> = emptyList(),
    val latencyMs: Int = -1,
) {
    fun toCMSSite(): CMSSite = CMSSite(
        key = key,
        name = name,
        api = api,
        adult = adult,
        enabled = enabled,
        categories = categories,
        origin = "TVBox",
    )

    fun toJSON(): JSONObject = JSONObject()
        .put("key", key)
        .put("name", name)
        .put("api", api)
        .put("adult", adult)
        .put("enabled", enabled)
        .put("latencyMs", latencyMs)
        .put("categories", JSONArray(categories.map { JSONObject().put("id", it.id).put("name", it.name) }))

    companion object {
        fun fromJSON(o: JSONObject): TVBoxRepoSite {
            val cats = o.optJSONArray("categories") ?: JSONArray()
            return TVBoxRepoSite(
                key = o.optString("key").ifBlank { CMSSite.key(o.optString("api")) },
                name = o.optString("name", "站点"),
                api = o.optString("api"),
                adult = o.optBoolean("adult", false),
                enabled = o.optBoolean("enabled", true),
                categories = (0 until cats.length()).mapNotNull {
                    cats.optJSONObject(it)?.let { c -> CMSCategory(c.optString("id"), c.optString("name")) }
                },
                latencyMs = o.optInt("latencyMs", -1),
            )
        }
    }
}

data class TVBoxRepo(
    val id: String,
    val name: String,
    val url: String?,
    val sites: List<TVBoxRepoSite>,
    val lives: List<TVBoxRepoLive>,
    val skippedCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJSON(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url ?: "")
        .put("skippedCount", skippedCount)
        .put("updatedAt", updatedAt)
        .put("sites", JSONArray(sites.map { it.toJSON() }))
        .put("lives", JSONArray(lives.map { it.toJSON() }))

    companion object {
        fun fromJSON(o: JSONObject): TVBoxRepo {
            val sArr = o.optJSONArray("sites") ?: JSONArray()
            val lArr = o.optJSONArray("lives") ?: JSONArray()
            return TVBoxRepo(
                id = o.optString("id").ifBlank { TVBoxStore.hashId(o.optString("name") + o.optLong("updatedAt")) },
                name = o.optString("name", "TVBox 配置"),
                url = o.optString("url").takeIf { it.isNotBlank() },
                sites = (0 until sArr.length()).mapNotNull { sArr.optJSONObject(it)?.let(TVBoxRepoSite::fromJSON) },
                lives = (0 until lArr.length()).mapNotNull { lArr.optJSONObject(it)?.let(TVBoxRepoLive::fromJSON) },
                skippedCount = o.optInt("skippedCount", 0),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

data class TVBoxRecentWatch(
    val siteKey: String,
    val siteName: String,
    val seriesId: String,
    val title: String,
    val cover: String,
    val episodeIndex: Int,
    val episodeTitle: String,
    val lineName: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJSON(): JSONObject = JSONObject()
        .put("siteKey", siteKey)
        .put("siteName", siteName)
        .put("seriesId", seriesId)
        .put("title", title)
        .put("cover", cover)
        .put("episodeIndex", episodeIndex)
        .put("episodeTitle", episodeTitle)
        .put("lineName", lineName)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJSON(o: JSONObject) = TVBoxRecentWatch(
            siteKey = o.optString("siteKey"),
            siteName = o.optString("siteName"),
            seriesId = o.optString("seriesId"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            episodeIndex = o.optInt("episodeIndex", 0),
            episodeTitle = o.optString("episodeTitle", "第 1 集"),
            lineName = o.optString("lineName"),
            positionMs = o.optLong("positionMs", 0L),
            durationMs = o.optLong("durationMs", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/**
 * Multi-repo persistent store for TVBox VOD (MacCMS API) + Live (HTTPS M3U / TXT).
 * Keeps TVBox completely organized in its own tab without polluting Home short-drama tabs.
 */
class TVBoxStore(ctx: Context) {
    private val file = File(ctx.filesDir, "tvbox_store_v1.json")
    private val http = OkHttpClient.Builder()
        .callTimeout(18, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val repos = mutableStateListOf<TVBoxRepo>()
    var activeRepoId by mutableStateOf<String?>(null)
        private set
    val favoriteChannels = mutableStateListOf<String>()
    val recentWatches = mutableStateListOf<TVBoxRecentWatch>()

    val activeRepo: TVBoxRepo?
        get() = repos.firstOrNull { it.id == activeRepoId } ?: repos.firstOrNull()

    init {
        load()
    }

    fun migrateLegacySitesIfNeeded(legacyCmsSites: List<CMSSite>) {
        if (repos.isNotEmpty()) return
        val tvSites = legacyCmsSites.filter { it.origin.equals("TVBox", ignoreCase = true) || it.origin.startsWith("TVBox") }
        if (tvSites.isEmpty()) return
        val repo = TVBoxRepo(
            id = "legacy-tvbox",
            name = "已导入 TVBox",
            url = null,
            sites = tvSites.map {
                TVBoxRepoSite(it.key, it.name, it.api, it.adult, it.enabled, it.categories, -1)
            },
            lives = emptyList(),
        )
        repos.add(repo)
        activeRepoId = repo.id
        save()
    }

    fun selectRepo(id: String) {
        if (repos.any { it.id == id }) {
            activeRepoId = id
            save()
        }
    }

    fun removeRepo(id: String) {
        repos.removeAll { it.id == id }
        if (activeRepoId == id) activeRepoId = repos.firstOrNull()?.id
        save()
    }

    fun setSiteEnabled(repoId: String, siteKey: String, enabled: Boolean) {
        val idx = repos.indexOfFirst { it.id == repoId }
        if (idx < 0) return
        val r = repos[idx]
        repos[idx] = r.copy(sites = r.sites.map { if (it.key == siteKey) it.copy(enabled = enabled) else it })
        save()
    }

    fun setLiveEnabled(repoId: String, liveId: String, enabled: Boolean) {
        val idx = repos.indexOfFirst { it.id == repoId }
        if (idx < 0) return
        val r = repos[idx]
        repos[idx] = r.copy(lives = r.lives.map { if (it.id == liveId) it.copy(enabled = enabled) else it })
        save()
    }

    fun cacheSiteCategories(repoId: String, siteKey: String, cats: List<CMSCategory>) {
        val idx = repos.indexOfFirst { it.id == repoId }
        if (idx < 0) return
        val r = repos[idx]
        val old = r.sites.firstOrNull { it.key == siteKey } ?: return
        if (old.categories == cats) return
        repos[idx] = r.copy(sites = r.sites.map { if (it.key == siteKey) it.copy(categories = cats) else it })
        save()
    }

    fun findSite(siteKey: String): TVBoxRepoSite? {
        activeRepo?.sites?.firstOrNull { it.key == siteKey }?.let { return it }
        return repos.asSequence().flatMap { it.sites.asSequence() }.firstOrNull { it.key == siteKey }
    }

    fun isFavoriteChannel(name: String): Boolean = name in favoriteChannels

    fun toggleFavoriteChannel(name: String) {
        if (name in favoriteChannels) favoriteChannels.remove(name) else favoriteChannels.add(0, name)
        save()
    }

    fun recordWatch(item: TVBoxRecentWatch) {
        recentWatches.removeAll { it.seriesId == item.seriesId }
        recentWatches.add(0, item)
        while (recentWatches.size > 30) recentWatches.removeAt(recentWatches.lastIndex)
        save()
    }

    suspend fun addManualSite(name: String, apiRaw: String, adult: Boolean): TVBoxRepoSite = withContext(Dispatchers.IO) {
        val api = MacCMS.normalize(apiRaw)
        val t0 = System.currentTimeMillis()
        val cats = MacCMS.fetchCategories(api)
        val ms = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
        val host = runCatching { URI(api).host }.getOrNull() ?: "采集站"
        val site = TVBoxRepoSite(CMSSite.key(api), name.ifBlank { host }, api, adult, true, cats, ms)
        withContext(Dispatchers.Main) {
            val cur = activeRepo
            if (cur == null) {
                val repo = TVBoxRepo("manual-repo", "自建配置", null, listOf(site), emptyList())
                repos.add(0, repo)
                activeRepoId = repo.id
            } else {
                val idx = repos.indexOfFirst { it.id == cur.id }
                val updated = cur.sites.filterNot { it.key == site.key } + site
                repos[idx] = cur.copy(sites = updated, updatedAt = System.currentTimeMillis())
            }
            save()
        }
        site
    }

    /** Imports an HTTPS TVBox JSON URL or standalone HTTPS M3U/TXT live list URL. */
    suspend fun importFromUrl(rawUrl: String, onProgress: (String) -> Unit = {}): TVBoxRepo = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        require(url.startsWith("https://")) { "仅支持 HTTPS 配置地址" }
        onProgress("正在拉取配置…")
        val body = downloadText(url)
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("{")) {
            val channels = parseLiveText(body)
            require(channels.isNotEmpty()) { "该链接既不是 TVBox JSON，也不是有效的 HTTPS 直播列表" }
            val host = runCatching { URI(url).host }.getOrNull() ?: "直播源"
            val liveName = url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').ifBlank { host }
            val live = TVBoxRepoLive(hashId(url), liveName, url, true, channels)
            val repo = TVBoxRepo(hashId(url), liveName, url, emptyList(), listOf(live))
            withContext(Dispatchers.Main) { upsertAndActivate(repo) }
            return@withContext repo
        }
        importParsedJson(body, sourceUrl = url, fallbackName = null, onProgress = onProgress)
    }

    /** Imports pasted JSON text or M3U/TXT live text. */
    suspend fun importFromText(rawText: String, fallbackName: String? = null, onProgress: (String) -> Unit = {}): TVBoxRepo = withContext(Dispatchers.IO) {
        val trimmed = rawText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("{")) {
            val channels = parseLiveText(rawText)
            require(channels.isNotEmpty()) { "未识别到 TVBox JSON 或有效 HTTPS 直播源" }
            val title = fallbackName?.takeIf { it.isNotBlank() } ?: "本地直播源"
            val id = hashId(title + channels.size + channels.first().urlKey())
            val live = TVBoxRepoLive(id, title, "", true, channels)
            val repo = TVBoxRepo(id, title, null, emptyList(), listOf(live))
            withContext(Dispatchers.Main) { upsertAndActivate(repo) }
            return@withContext repo
        }
        importParsedJson(rawText, sourceUrl = null, fallbackName = fallbackName, onProgress = onProgress)
    }

    suspend fun refreshRepo(repoId: String, onProgress: (String) -> Unit = {}): TVBoxRepo {
        val existing = repos.firstOrNull { it.id == repoId } ?: error("配置不存在")
        val url = existing.url ?: error("本地导入的配置没有远程地址")
        return importFromUrl(url, onProgress)
    }

    suspend fun pingAllSites(repoId: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val repo = repos.firstOrNull { it.id == repoId } ?: return@withContext
        val updated = ArrayList<TVBoxRepoSite>(repo.sites.size)
        var done = 0
        for (batch in repo.sites.chunked(6)) {
            val res = coroutineScope {
                batch.map { s ->
                    async {
                        val t0 = System.currentTimeMillis()
                        val cats = runCatching { MacCMS.fetchCategories(s.api) }.getOrNull()
                        val ms = if (cats != null) (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1) else 9999
                        s.copy(categories = cats ?: s.categories, latencyMs = ms)
                    }
                }.awaitAll()
            }
            updated.addAll(res)
            done += res.size
            withContext(Dispatchers.Main) { onProgress("测速中 $done/${repo.sites.size}") }
        }
        withContext(Dispatchers.Main) {
            val idx = repos.indexOfFirst { it.id == repoId }
            if (idx >= 0) {
                repos[idx] = repos[idx].copy(sites = updated)
                save()
            }
        }
    }

    suspend fun loadLiveChannels(repoId: String, liveId: String, forceRefresh: Boolean = false): List<TVBoxLiveChannel> = withContext(Dispatchers.IO) {
        val repo = repos.firstOrNull { it.id == repoId } ?: return@withContext emptyList()
        val live = repo.lives.firstOrNull { it.id == liveId } ?: return@withContext emptyList()
        if (!forceRefresh && live.channels.isNotEmpty()) return@withContext live.channels
        if (live.url.isBlank()) return@withContext live.channels
        val raw = downloadText(live.url)
        val parsed = parseLiveText(raw)
        withContext(Dispatchers.Main) {
            val rIdx = repos.indexOfFirst { it.id == repoId }
            if (rIdx >= 0) {
                val r = repos[rIdx]
                repos[rIdx] = r.copy(lives = r.lives.map { if (it.id == liveId) it.copy(channels = parsed) else it })
                save()
            }
        }
        parsed
    }

    private suspend fun importParsedJson(
        jsonText: String,
        sourceUrl: String?,
        fallbackName: String?,
        onProgress: (String) -> Unit,
    ): TVBoxRepo {
        val parsed = TVBoxImport.parse(jsonText)
        val validSites = arrayListOf<TVBoxRepoSite>()
        val validationSites = if (parsed.sites.size > 60) parsed.sites.take(8) else parsed.sites
        var checked = 0
        for (batch in validationSites.chunked(8)) {
            val batchRes = coroutineScope {
                batch.map { site ->
                    async {
                        val t0 = System.currentTimeMillis()
                        val cats = runCatching { MacCMS.fetchCategories(site.api) }.getOrNull()
                        val ms = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                        site to (cats?.let { it to ms })
                    }
                }.awaitAll()
            }
            for ((site, ok) in batchRes) {
                checked++
                if (ok != null) {
                    validSites.add(
                        TVBoxRepoSite(
                            key = CMSSite.key(site.api),
                            name = site.name,
                            api = site.api,
                            adult = site.adult,
                            enabled = true,
                            categories = ok.first,
                            latencyMs = ok.second,
                        ),
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onProgress("正在验证点播站 $checked/${validationSites.size}（可用 ${validSites.size}）")
            }
        }
        val finalSites: List<TVBoxRepoSite> = if (validationSites.size < parsed.sites.size) {
            require(validSites.isNotEmpty() || parsed.lives.isNotEmpty()) {
                "大配置没有验证出可用的苹果 CMS 站点（已跳过 ${parsed.skipped} 个不兼容项）"
            }
            val verified = validSites.associateBy { it.key }
            val merged = parsed.sites.map { site ->
                verified[CMSSite.key(site.api)] ?: TVBoxRepoSite(
                    key = CMSSite.key(site.api), name = site.name, api = site.api,
                    adult = site.adult, enabled = true, categories = emptyList(), latencyMs = -1,
                )
            }
            withContext(Dispatchers.Main) { onProgress("大配置已导入 ${merged.size} 个点播站，其余进入后按需验证") }
            merged
        } else {
            validSites
        }
        val lives = parsed.lives.map { l ->
            TVBoxRepoLive(id = hashId(l.url), name = l.name, url = l.url, enabled = true, channels = emptyList())
        }
        require(finalSites.isNotEmpty() || lives.isNotEmpty()) {
            "配置里没有可用的苹果 CMS 点播站或 HTTPS 直播源（已跳过 ${parsed.skipped} 个不兼容项）"
        }
        val title = when {
            parsed.name != "TVBox 配置" -> parsed.name
            !fallbackName.isNullOrBlank() -> fallbackName
            !sourceUrl.isNullOrBlank() -> runCatching { URI(sourceUrl).host }.getOrNull() ?: parsed.name
            else -> parsed.name
        }
        val id = hashId(sourceUrl ?: (title + finalSites.firstOrNull()?.api.orEmpty() + lives.firstOrNull()?.url.orEmpty()))
        val repo = TVBoxRepo(
            id = id,
            name = title,
            url = sourceUrl,
            sites = finalSites,
            lives = lives,
            skippedCount = parsed.skipped + (parsed.sites.size - finalSites.size),
            updatedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.Main) { upsertAndActivate(repo) }
        return repo
    }

    private fun upsertAndActivate(repo: TVBoxRepo) {
        val idx = repos.indexOfFirst { it.id == repo.id || (repo.url != null && it.url == repo.url) }
        if (idx >= 0) {
            val old = repos[idx]
            val oldSiteEnabled = old.sites.associate { it.key to it.enabled }
            val oldLiveMap = old.lives.associateBy { it.id }
            val merged = repo.copy(
                id = old.id,
                sites = repo.sites.map { s -> s.copy(enabled = oldSiteEnabled[s.key] ?: s.enabled) },
                lives = repo.lives.map { l ->
                    val prev = oldLiveMap[l.id]
                    if (prev != null) l.copy(enabled = prev.enabled, channels = l.channels.ifEmpty { prev.channels }) else l
                },
            )
            repos[idx] = merged
            activeRepoId = merged.id
        } else {
            repos.add(0, repo)
            activeRepoId = repo.id
        }
        save()
    }

    private fun downloadText(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: error("地址无效")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "仅支持 HTTPS 公开地址" }
        require(!NetGuard.isIPLiteral(uri.host) && !NetGuard.resolvesToBlocked(uri.host)) { "不支持 IP 或内网地址" }
        val req = Request.Builder().url(url).header("User-Agent", MacCMS.UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} · ${uri.host}")
            val finalHost = resp.request.url.host
            if (finalHost != uri.host && NetGuard.resolvesToBlocked(finalHost)) error("跳转到内网地址")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            require(bytes.size <= 4 shl 20) { "文件超过 4 MB 限制" }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            activeRepoId = root.optString("activeRepoId").takeIf { it.isNotBlank() }
            val rArr = root.optJSONArray("repos") ?: JSONArray()
            repos.clear()
            for (i in 0 until rArr.length()) rArr.optJSONObject(i)?.let { repos.add(TVBoxRepo.fromJSON(it)) }
            val fArr = root.optJSONArray("favoriteChannels") ?: JSONArray()
            favoriteChannels.clear()
            for (i in 0 until fArr.length()) fArr.optString(i).takeIf { it.isNotBlank() }?.let { favoriteChannels.add(it) }
            val wArr = root.optJSONArray("recentWatches") ?: JSONArray()
            recentWatches.clear()
            for (i in 0 until wArr.length()) wArr.optJSONObject(i)?.let { recentWatches.add(TVBoxRecentWatch.fromJSON(it)) }
        }
    }

    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tvbox-save").apply { isDaemon = true }
    }
    @Volatile private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    private fun save() {
        val snapActive = activeRepoId ?: ""
        val snapRepos = repos.toList()
        val snapFavs = favoriteChannels.toList()
        val snapRecent = recentWatches.toList()
        pendingSave?.cancel(false)
        pendingSave = saveExecutor.schedule({
            runCatching {
                val root = JSONObject()
                    .put("activeRepoId", snapActive)
                    .put("repos", JSONArray(snapRepos.map { it.toJSON() }))
                    .put("favoriteChannels", JSONArray(snapFavs))
                    .put("recentWatches", JSONArray(snapRecent.map { it.toJSON() }))
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) file.writeText(root.toString(), Charsets.UTF_8)
            }
        }, 200L, TimeUnit.MILLISECONDS)
    }

    private fun TVBoxLiveChannel.urlKey(): String = urls.firstOrNull().orEmpty()

    companion object {
        fun hashId(seed: String): String =
            MessageDigest.getInstance("MD5").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

        /**
         * Parses both extended M3U (`#EXTM3U` / `#EXTINF`) and TVBox TXT (`分组,#genre#` + `频道,url#url2`).
         * Deduplicates channels by name into multi-line `urls` so users can switch backup lines during playback.
         */
        fun parseLiveText(raw: String): List<TVBoxLiveChannel> {
            val cleaned = raw.trimStart('\uFEFF')
            val grouped = LinkedHashMap<String, MutableChannel>()
            var currentGroup = "默认频道"
            var pendingName: String? = null
            var pendingGroup: String? = null
            var pendingLogo = ""

            fun addChannel(name: String, group: String, logo: String, rawUrlPart: String) {
                val n = name.trim().take(60)
                if (n.isEmpty()) return
                val candidates = rawUrlPart.split('#').map { it.trim() }.filter { isSafeStreamUrl(it) }
                if (candidates.isEmpty()) return
                val entry = grouped.getOrPut(n) { MutableChannel(n, group.ifBlank { "默认频道" }, logo) }
                for (u in candidates) if (u !in entry.urls) entry.urls.add(u)
            }

            for (lineRaw in cleaned.lineSequence()) {
                val line = lineRaw.trim()
                if (line.isEmpty() || line.startsWith("#EXTM3U", ignoreCase = true)) continue
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    pendingGroup = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.trim()
                    pendingLogo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    continue
                }
                if (line.startsWith("#")) continue

                if (pendingName != null) {
                    addChannel(pendingName!!, pendingGroup ?: currentGroup, pendingLogo, line)
                    pendingName = null
                    pendingGroup = null
                    pendingLogo = ""
                    continue
                }

                val comma = line.indexOf(',')
                if (comma > 0) {
                    val left = line.substring(0, comma).trim()
                    val right = line.substring(comma + 1).trim()
                    if (right.equals("#genre#", ignoreCase = true)) {
                        if (left.isNotEmpty()) currentGroup = left.take(30)
                    } else {
                        addChannel(left, currentGroup, "", right)
                    }
                }
            }
            return grouped.values.map { TVBoxLiveChannel(it.name, it.group, it.urls.toList(), it.logo) }
        }

        private fun isSafeStreamUrl(raw: String): Boolean {
            val u = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
            if (!u.startsWith("https://")) return false
            val uri = runCatching { URI(u) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            if (host.isBlank() || NetGuard.isIPLiteral(host)) return false
            return true
        }

        private class MutableChannel(val name: String, val group: String, val logo: String) {
            val urls = ArrayList<String>(2)
        }
    }
}
