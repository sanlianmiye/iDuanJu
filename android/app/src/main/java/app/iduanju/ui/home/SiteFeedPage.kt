package app.iduanju.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory feed cache so navigating into DetailScreen and pressing Back never re-fetches the site/plugin
 * from scratch or loses the selected category and scroll position. Pull-to-refresh clears and reloads.
 */
private object FeedGridCache {
    class Bucket(
        val items: MutableList<HGSeries> = mutableListOf(),
        var page: Int = 1,
        var hasMore: Boolean = true,
        var loaded: Boolean = false,
        var firstIndex: Int = 0,
        var firstOffset: Int = 0,
    )
    val categories = HashMap<String, List<Pair<String, String>>>()
    val selectedTab = HashMap<String, Int>()
    val buckets = HashMap<String, Bucket>()

    fun bucket(key: String, cat: String): Bucket = buckets.getOrPut("$key|$cat") { Bucket() }
}

/** iOS HomeFeedPage for `.site(cms)`. */
@Composable
fun SiteFeedPage(site: CMSSite, onOpen: (String) -> Unit) {
    LaunchedEffect(site.key) {
        if (site.categories.isEmpty()) {
            val categories = withContext(Dispatchers.IO) { runCatching { MacCMS.fetchCategories(site.api) }.getOrNull() }
            if (!categories.isNullOrEmpty()) IdjApp.instance.cms.cacheCategories(site.key, categories)
        }
    }
    FeedGrid("cms:" + site.key, listOf("" to "最新") + site.categories.map { it.id to it.name }, { cat, page ->
        val o = MacCMS.fetch(site.api, buildMap { put("ac", "detail"); put("pg", page.toString()); if (cat.isNotEmpty()) put("t", cat) })
        if (cat.isEmpty() && page == 1) MacCMS.parseCategories(o).takeIf { it.isNotEmpty() }?.let { IdjApp.instance.cms.cacheCategories(site.key, it) }
        MacCMS.page(o, site.key, page)
    }, onOpen)
}

/** iOS HomeFeedPage for `.site(plugin)` (JSVideoProvider): 推荐 + the categories `home()` returned. */
@Composable
fun PluginFeedPage(p: app.iduanju.plugin.PluginStore.Installed, onOpen: (String) -> Unit) {
    val cacheKey = "plugin:" + p.id
    var cats by remember(p.id) { mutableStateOf(FeedGridCache.categories[cacheKey] ?: listOf("" to "推荐")) }
    FeedGrid(cacheKey, cats, { cat, page ->
        val v = IdjApp.instance.plugins.video(p.id)
        try {
            val r = v.list(cat, page)
            if (cat.isEmpty()) v.categories.takeIf { it.isNotEmpty() }?.let { cs ->
                val list = listOf("" to "推荐") + cs.map { it.id to it.name }
                FeedGridCache.categories[cacheKey] = list
                cats = list
            }
            r
        } catch (e: app.iduanju.core.plugin.PluginException) { throw Exception(e.friendly) }
    }, onOpen)
}

/** 最新/推荐 + category chips, 3-column poster grid with in-memory cache, pull-to-refresh, and paging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedGrid(key: String, cats: List<Pair<String, String>>, load: (String, Int) -> app.iduanju.core.hongguo.HGPage, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    val scope = rememberCoroutineScope()
    var sel by remember(key) { mutableIntStateOf((FeedGridCache.selectedTab[key] ?: 0).coerceIn(0, (cats.size - 1).coerceAtLeast(0))) }
    val activeCat = cats.getOrNull(sel)?.first ?: ""
    val bucket = remember(key, activeCat) { FeedGridCache.bucket(key, activeCat) }
    val items = remember(key, activeCat) { mutableStateListOf<HGSeries>().apply { addAll(bucket.items) } }
    var page by remember(key, activeCat) { mutableIntStateOf(bucket.page) }
    var hasMore by remember(key, activeCat) { mutableStateOf(bucket.hasMore) }
    var loading by remember(key, activeCat) { mutableStateOf(false) }
    var refreshing by remember(key, activeCat) { mutableStateOf(false) }
    var error by remember(key, activeCat) { mutableStateOf<String?>(null) }
    var verify by remember(key, activeCat) { mutableStateOf<app.iduanju.core.util.VerificationRequiredException?>(null) }
    val grid = rememberLazyGridState(
        initialFirstVisibleItemIndex = bucket.firstIndex,
        initialFirstVisibleItemScrollOffset = bucket.firstOffset,
    )

    DisposableEffect(key, activeCat, grid) {
        onDispose {
            bucket.firstIndex = grid.firstVisibleItemIndex
            bucket.firstOffset = grid.firstVisibleItemScrollOffset
        }
    }

    suspend fun more(reset: Boolean = false) {
        if (loading) return
        if (!reset && !hasMore) return
        loading = true
        if (reset) refreshing = true
        try {
            val targetPage = if (reset) 1 else page
            val p = withContext(Dispatchers.IO) { load(activeCat, targetPage) }
            if (reset) {
                items.clear()
                bucket.items.clear()
            }
            val seen = items.map { it.seriesId }.toHashSet()
            val fresh = p.items.filter { seen.add(it.seriesId) }
            items.addAll(fresh)
            bucket.items.addAll(fresh)
            // TV fix: a page with zero new items means the source is repeating itself — stop,
            // otherwise the near-end effect below re-fires forever and the grid flickers.
            hasMore = p.hasMore && fresh.isNotEmpty()
            page = targetPage + 1
            bucket.hasMore = hasMore
            bucket.page = page
            bucket.loaded = true
            error = null
            verify = null
        } catch (e: CancellationException) { throw e }
        catch (e: app.iduanju.core.util.VerificationRequiredException) { verify = e; error = "需要验证" }
        catch (e: Exception) { error = e.message ?: "加载失败" }
        finally {
            loading = false
            refreshing = false
        }
    }
    LaunchedEffect(key, activeCat) {
        if (!bucket.loaded && items.isEmpty()) more()
    }
    // TV fix: "near end" only counts when the content actually overflows the viewport;
    // on a big TV screen a short list is fully visible, which used to read as permanently
    // near-end and drove an endless load loop (visible as constant flicker).
    val nearEnd by remember { derivedStateOf { val info = grid.layoutInfo; info.totalItemsCount > info.visibleItemsInfo.size && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.isNotEmpty()) more() }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { more(reset = true) } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(GridCells.Fixed(app.iduanju.ui.components.LocalLayoutMetrics.current.posterCols), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(cats) { i, (_, name) ->
                            val on = i == sel
                            Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (on) c.text else c.text.copy(0.08f))
                                .clickable {
                                    sel = i
                                    FeedGridCache.selectedTab[key] = i
                                }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text(name, color = if (on) c.bg else c.text.copy(0.85f), fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                items(items, key = { it.seriesId }) { HomePosterCard(it, onOpen) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading && !refreshing -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            verify != null -> app.iduanju.huangguo.VerifyRequiredState(verify!!) { scope.launch { more() } }
                            error != null -> Text(error!! + " · 点击重试", color = c.secondary, fontSize = 13.sp, modifier = Modifier.clickable { scope.launch { more() } })
                            items.isEmpty() && !loading -> Text("该分类暂无内容，请尝试具体子分类", color = c.secondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 92.dp)
                .size(44.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(c.surface.copy(alpha = 0.92f))
                .border(1.dp, c.hairline, CircleShape)
                .clickable(enabled = !loading) {
                    scope.launch {
                        grid.animateScrollToItem(0)
                        more(reset = true)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) {
                CircularProgressIndicator(color = c.text, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新列表", tint = c.text, modifier = Modifier.size(20.dp))
            }
        }
    }
}
