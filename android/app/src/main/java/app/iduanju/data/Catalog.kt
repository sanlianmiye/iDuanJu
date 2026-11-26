package app.iduanju.data

import app.iduanju.IdjApp
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGResolvedVideo
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.plugin.PluginException
import app.iduanju.core.plugin.PluginVideo
import app.iduanju.core.util.ProviderException
import app.iduanju.core.tg.TGVideo

/** Routes a namespaced series id to its provider (红果 by default, "cms:<key>:<id>" → MacCMS, "plugin:<id>:…" → idj1 plugin). */
object Catalog {
    private const val DETAIL_TTL_MS = 5 * 60_000L
    private val previewHints = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<HGSeriesDetail, Long>>()
    private val detailLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun rememberPreview(sid: String, title: String, cover: String) {
        if (sid.isNotEmpty() && (title.isNotEmpty() || cover.isNotEmpty())) {
            previewHints[sid] = title to cover
        }
    }

    fun peekCover(sid: String): String {
        previewHints[sid]?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        detailCache[sid]?.first?.cover?.takeIf { it.isNotEmpty() }?.let { return it }
        IdjApp.instance.library.progress(sid)?.cover?.takeIf { it.isNotEmpty() }?.let { return it }
        IdjApp.instance.library.favorites[sid]?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    fun peekTitle(sid: String): String {
        previewHints[sid]?.first?.takeIf { it.isNotEmpty() }?.let { return it }
        detailCache[sid]?.first?.title?.takeIf { it.isNotEmpty() }?.let { return it }
        IdjApp.instance.library.progress(sid)?.title?.takeIf { it.isNotEmpty() }?.let { return it }
        IdjApp.instance.library.favorites[sid]?.first?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    fun isCMS(sid: String) = sid.startsWith("cms:")
    fun isHongguo(sid: String) = !isCMS(sid) && !PluginVideo.isPlugin(sid) &&
        !app.iduanju.core.huangguo.HuangguoClient.isHuangguo(sid) && !TGVideo.isTG(sid)

    private fun cmsSite(sid: String) = MacCMS.parseSid(sid)?.first?.let { IdjApp.instance.cms.site(it) }
        ?: throw ProviderException.invalid("采集站已删除")

    private fun plugin(sid: String): PluginVideo = PluginVideo.pluginId(sid)?.let { IdjApp.instance.plugins.video(it) }
        ?: throw ProviderException.invalid("插件已删除")

    private inline fun <T> friendly(f: () -> T): T = try { f() } catch (e: PluginException) { throw ProviderException(e.friendly) }

    private fun cachedDetail(sid: String): HGSeriesDetail? {
        val hit = detailCache[sid] ?: return null
        if (System.currentTimeMillis() - hit.second < DETAIL_TTL_MS && hit.first.episodes.isNotEmpty()) return hit.first
        detailCache.remove(sid, hit)
        return null
    }

    fun detail(sid: String, web: Boolean = true): HGSeriesDetail {
        cachedDetail(sid)?.let { cached ->
            if (!web || !isHongguo(sid) || cached.cast.isNotEmpty() || cached.tags.isNotEmpty()) return cached
        }
        val lock = detailLocks.computeIfAbsent(sid) { Any() }
        return synchronized(lock) {
            cachedDetail(sid)?.let { cached ->
                if (!web || !isHongguo(sid) || cached.cast.isNotEmpty() || cached.tags.isNotEmpty()) return@synchronized cached
                val enriched = enrich(sid, cached) ?: cached
                return@synchronized enriched
            }
            val loaded = when {
                isCMS(sid) -> MacCMS.detail(cmsSite(sid), sid)
                PluginVideo.isPlugin(sid) -> friendly { plugin(sid).detail(sid) }
                app.iduanju.core.huangguo.HuangguoClient.isHuangguo(sid) -> IdjApp.instance.huangguo.detail(sid)
                TGVideo.isTG(sid) -> IdjApp.instance.tg.let { TGVideo.detail(it.client, sid, it.channel(sid.removePrefix("tg:").substringBefore(':'))) }
                else -> IdjApp.instance.hongguo.detail(sid, web)
            }
            if (loaded.episodes.isNotEmpty()) detailCache[sid] = loaded to System.currentTimeMillis()
            rememberPreview(sid, loaded.title, loaded.cover)
            loaded
        }
    }

    /** Second-pass official web enrichment for Hongguo (cast / tags / rating), mirroring iOS Catalog.enriched. */
    fun enrich(sid: String, base: HGSeriesDetail): HGSeriesDetail? {
        if (!isHongguo(sid) || (base.cast.isNotEmpty() && base.tags.isNotEmpty())) return null
        val w = try { app.iduanju.core.hongguo.HGDetailExtras.webDetail(sid) } catch (_: Exception) { return null }
        val cur = cachedDetail(sid) ?: base
        val out = app.iduanju.core.hongguo.HGDetailExtras.enrich(cur, w)
        if (out.episodes.isNotEmpty()) detailCache[sid] = out to System.currentTimeMillis()
        rememberPreview(sid, out.title, out.cover)
        return out
    }

    fun resolve(ep: HGEpisode): HGResolvedVideo = when {
        isCMS(ep.vid) -> MacCMS.resolve(cmsSite(ep.vid), ep)
        PluginVideo.isPlugin(ep.vid) -> friendly { plugin(ep.vid).resolve(ep) }
        app.iduanju.core.huangguo.HuangguoClient.isHuangguo(ep.vid) -> IdjApp.instance.huangguo.resolve(ep)
        TGVideo.isTG(ep.vid) -> TGVideo.resolve(IdjApp.instance.tg.client, ep)
        else -> IdjApp.instance.hongguo.resolve(ep.vid)
    }
}
