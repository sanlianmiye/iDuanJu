package app.iduanju.ui.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.iduanju.ui.components.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mirrors iOS HomeSource / HomeTab (HomeView.swift). */
enum class HomeSource(val label: String) { hongguo("红果"), huangguo("黄果"), ai("AI剧"), channels("频道"), manga("漫画"), novel("小说") }

sealed class HomeTab(val subLabel: String) {
    data class Hongguo(val g: HGRankGenre) : HomeTab(
        when (g) { HGRankGenre.all -> "全部榜单"; HGRankGenre.ai -> "红果 AI剧"; else -> g.label }
    )
    data object ActorRank : HomeTab("剧星榜")
}

fun HomeSource.subs(): List<HomeTab> = when (this) {
    HomeSource.hongguo -> listOf(HomeTab.Hongguo(HGRankGenre.all), HomeTab.Hongguo(HGRankGenre.human), HomeTab.Hongguo(HGRankGenre.comic), HomeTab.ActorRank)
    HomeSource.ai -> listOf(HomeTab.Hongguo(HGRankGenre.ai))
    else -> emptyList()
}

data class BoardSpec(val genre: HGRankGenre, val sub: HGRankSub) {
    val id get() = "hg-${genre.name}-${sub.name}"
    val title get() = (if (genre == HGRankGenre.all) "" else genre.label) + sub.label + "榜"
}

/** First-page cache per board (iOS BoardCache). */
object BoardCache {
    val items = mutableStateMapOf<String, List<HGSeries>>()
    val failed = mutableStateMapOf<String, String>()
    private val loading = HashSet<String>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    suspend fun load(b: BoardSpec, force: Boolean = false) {
        synchronized(loading) { if (b.id in loading || (!force && items.containsKey(b.id))) return; loading.add(b.id) }
        // Repository lifetime, not the current composition: even an early navigation away keeps the result.
        scope.launch {
            try {
                android.util.Log.i("IDJ", "board fetch ${b.id}")
                val r = withContext(Dispatchers.IO) { IdjApp.instance.hongguo.rank(b.sub, b.genre).items }
                items[b.id] = r; failed.remove(b.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { failed[b.id] = e.message ?: "加载失败"; android.util.Log.w("IDJ", "board ${b.id} failed: $e") }
            finally { synchronized(loading) { loading.remove(b.id) } }
        }
    }
}

object HomeFmt {
    fun count(n: Long): String = when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
        else -> n.toString()
    }
    fun metaLine(s: HGSeries): String = when {
        s.episodeCount > 0 && s.playCount > 0 -> "${s.episodeCount}集 · ${count(s.playCount)}热度"
        s.episodeCount > 0 -> "${s.episodeCount}集"
        s.playCount > 0 -> count(s.playCount) + "热度"
        else -> s.subtitle ?: ""
    }
    fun meta(s: HGSeries): String {
        val genres = s.tags.take(2).joinToString(" · ")
        val parts = listOfNotNull(genres.ifEmpty { null }, if (s.episodeCount > 0) "${s.episodeCount}集" else null)
        return if (parts.isEmpty()) metaLine(s) else parts.joinToString(" · ")
    }
    /** Only 「更新至N集 / 全N集」. */
    fun progress(s: HGSeries): String? {
        s.subtitle?.let { if (it.contains("更新至") || it.startsWith("全")) return it }
        return if (s.episodeCount > 0) "全${s.episodeCount}集" else null
    }
}

fun formatCount(n: Long) = HomeFmt.count(n)

@Composable
fun HomeScreen(onOpenDetail: (String) -> Unit, onSearch: () -> Unit = {}, onPlay: (String) -> Unit = {}, onOpenManga: (String) -> Unit = {}, onImmersiveChanged: (Boolean) -> Unit = {}) {
    val c = IdjThemeColors
    // iOS HomeSource.all: [红果, 黄果, AI剧] + visible sites (采集站 / plugins) + [频道, 漫画]
    val adult = IdjApp.instance.cms.adultUnlocked
    val sites = IdjApp.instance.cms.visibleSites.filterNot { it.origin.equals("TVBox", ignoreCase = true) || it.origin.startsWith("TVBox") }
    val plugins = IdjApp.instance.plugins.visible(adult).filter { it.type == "video" || it.type == "shortdrama" }
    val baseSources: List<Any> = if (adult) listOf(HomeSource.hongguo, HomeSource.huangguo, HomeSource.ai) else listOf(HomeSource.hongguo, HomeSource.ai)
    val pages: List<Any> = baseSources + sites + plugins + listOf(HomeSource.channels, HomeSource.manga, HomeSource.novel)
    val labels = pages.map { when (it) { is HomeSource -> it.label; is app.iduanju.core.cms.CMSSite -> it.name; else -> (it as app.iduanju.plugin.PluginStore.Installed).name } }
    val pager = rememberPagerState { pages.size }
    LaunchedEffect(HomeJump.label, pages.size) {
        val i = labels.indexOf(HomeJump.label ?: return@LaunchedEffect)
        if (i >= 0) { pager.scrollToPage(i); HomeJump.label = null }
    }
    val scope = rememberCoroutineScope()
    var feedScroll by remember { mutableFloatStateOf(0f) }
    val feedScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                feedScroll = (feedScroll - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }
    val source = pages[pager.currentPage.coerceIn(0, pages.size - 1)]
    var novelFullScreen by remember { mutableStateOf(false) }
    val immersive = source == HomeSource.novel && novelFullScreen
    LaunchedEffect(immersive) { onImmersiveChanged(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChanged(false) } }
    LaunchedEffect(pager.currentPage) { feedScroll = 0f }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pinned by remember(density) { derivedStateOf { feedScroll > with(density) { 8.dp.toPx() } } }
    // TV build: land initial DPAD focus on the search capsule.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    // Local backdrop of what sits behind the header: page colour + ambient glow. The header is a sibling drawn on top
    // of this layer, so its search capsule gets real drawBackdrop lens refraction (LIQUID) / blur (FROSTED). The
    // root backdrop can't be used here: this page lives inside it and would sample itself.
    val bgColor = c.bg
    val homeBackdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().layerBackdrop(homeBackdrop)) {
            // B-style home: deep blue-violet gradient + bokeh spots replace the warm glow (dark theme only).
            if (c.dark) BStyleBackdrop() else AmbientGlow(visible = !pinned)
        }
        Column(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.statusBarsPadding())) {
            // header: B-style glass bar — brand + search capsule + filter + clock
            if (!immersive) Column(Modifier.dsPinned(pinned).padding(top = 8.dp)) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp)
                        // same glass material as the dock / detail chrome (follows 我的 → 玻璃质感)
                        .glassSurface(RoundedCornerShape(50), backdrop = homeBackdrop, shadowRadius = 0.dp, fallbackAlpha = if (c.dark) 0.60f else 0.80f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // brand: play-in-circle + 汇看 wordmark (matches the app icon / B mock)
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).border(2.dp, if (c.dark) Color.White else c.text, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, null, tint = if (c.dark) Color.White else c.text, modifier = Modifier.size(19.dp).offset(x = 1.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("汇看", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    Spacer(Modifier.width(18.dp))
                    Row(Modifier.weight(1f).height(40.dp).clip(CircleShape)
                        .background(if (c.dark) Color.White.copy(0.08f) else c.glass)
                        .border(1.dp, if (c.dark) Color.White.copy(0.12f) else c.hairline, CircleShape)
                        .focusRequester(searchFocus).pressClick(label = "home.search", ring = CircleShape, ringColor = if (c.dark) Color.White else null) { onSearch() }
                        .padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("搜索你想看的作品", color = c.secondary, fontSize = 15.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.height(40.dp).clip(CircleShape)
                        .background(if (c.dark) Color.White.copy(0.08f) else c.glass)
                        .border(1.dp, if (c.dark) Color.White.copy(0.12f) else c.hairline, CircleShape)
                        .pressClick(label = "筛选", ring = CircleShape, ringColor = if (c.dark) Color.White else null) { HomeChannelSheetOpen.value = true }
                        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FilterList, null, tint = c.text, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("筛选", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    // live clock, as in the B mock's top-right corner
                    var clockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { clockMs = System.currentTimeMillis(); delay(20_000) } }
                    Text(
                        remember(clockMs) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(clockMs)) },
                        color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(start = 14.dp, end = 16.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                var sheet by HomeChannelSheetOpen
                ChannelBar(labels, pager.currentPage, c.text, onMore = { sheet = true }) { scope.launch { pager.animateScrollToPage(it) } }
                if (sheet) ChannelSheet(pages, labels, pager.currentPage, onDismiss = { sheet = false }) { page, sub ->
                    sheet = false
                    if (sub != null) HomeSubSel.map[labels[page]] = sub
                    scope.launch { pager.animateScrollToPage(page) }
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize().nestedScroll(feedScrollConnection), userScrollEnabled = !immersive, beyondViewportPageCount = 0) { page ->
                val any = pages.getOrElse(page) { pages.first() }
                if (any is app.iduanju.core.cms.CMSSite) { SiteFeedPage(any, onOpenDetail); return@HorizontalPager }
                if (any is app.iduanju.plugin.PluginStore.Installed) { PluginFeedPage(any, onOpenDetail); return@HorizontalPager }
                val s = any as HomeSource
                if (s == HomeSource.huangguo) { app.iduanju.huangguo.HuangguoFeedPage(onOpenDetail); return@HorizontalPager }
                if (s == HomeSource.manga) { app.iduanju.manga.MangaHomePage(onOpenManga); return@HorizontalPager }
                if (s == HomeSource.novel) {
                    app.iduanju.novel.NovelScreen(onBack = {}, embedded = true, onFullScreenChange = { novelFullScreen = it })
                    return@HorizontalPager
                }
                if (s == HomeSource.channels) { app.iduanju.tg.ChannelFeedPage(onPlay); return@HorizontalPager }
                if (s == HomeSource.ai && adult) {
                    // iOS AI剧: 红果 AI剧 | 黄果 AI 站 (the latter only behind the adult gate)
                    val aiTabs = listOf("红果 AI剧", "黄果 AI 站")
                    val sel = (HomeSubSel.map[s.label] ?: 0).coerceIn(0, 1)
                    Column(Modifier.fillMaxSize()) {
                        SegmentedTabs(
                            items = aiTabs,
                            selected = aiTabs[sel],
                            label = { it },
                            onSelect = { HomeSubSel.map[s.label] = aiTabs.indexOf(it) },
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            if (sel == 1) app.iduanju.huangguo.HuangguoFeedPage(onOpenDetail, aiOnly = true)
                            else FeedPage(s, onOpenDetail)
                        }
                    }
                    return@HorizontalPager
                }
                if (s.subs().isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("「${s.label}」Android 版尚未移植", color = c.secondary, fontSize = 14.sp)
                    }
                } else {
                    FeedPage(s, onOpenDetail)
                }
            }
        }
        // B-style remote hint pill (rail layouts only; decorative, never focusable).
        if (!immersive && LocalLayoutMetrics.current.useRail) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                .glassSurface(CircleShape, backdrop = homeBackdrop, shadowRadius = 8.dp, fallbackAlpha = 0.6f)
                .padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("方向键选择 · OK 打开 · 返回 上一页", color = c.secondary, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * B-style home ground: deep blue-violet gradient with soft bokeh spots.
 * Radial-gradient brushes only — no runtime blur, so it stays cheap on Android 9 TVs.
 */
@Composable
private fun BStyleBackdrop() {
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0A0E2A), 0.45f to Color(0xFF1C1446), 0.75f to Color(0xFF120E33), 1f to Color(0xFF070818),
            ),
        )
        val spots = listOf(
            Triple(Offset(size.width * 0.06f, size.height * 0.02f), 190.dp.toPx(), Color(0xFF7B4DFF).copy(alpha = 0.20f)),
            Triple(Offset(size.width * 0.92f, size.height * 0.00f), 230.dp.toPx(), Color(0xFF3D6BFF).copy(alpha = 0.16f)),
            Triple(Offset(size.width * 0.02f, size.height * 0.55f), 150.dp.toPx(), Color(0xFFC44DFF).copy(alpha = 0.12f)),
            Triple(Offset(size.width * 0.97f, size.height * 0.78f), 260.dp.toPx(), Color(0xFF5A3DFF).copy(alpha = 0.14f)),
            Triple(Offset(size.width * 0.55f, size.height * 1.02f), 200.dp.toPx(), Color(0xFF2E9BFF).copy(alpha = 0.08f)),
            Triple(Offset(size.width * 0.30f, size.height * 0.30f), 110.dp.toPx(), Color(0xFF8A5CFF).copy(alpha = 0.08f)),
        )
        for ((center, radius, color) in spots) {
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius = radius, center = center)
        }
    })
}

/**
 * iOS blurred cover backdrop. Modifier.blur only works on API 31+; on older systems decode a tiny
 * (16×22) thumbnail and let bilinear upscaling soften it, which reads as a heavy blur.
 */
@Composable
private fun BlurBackdrop(url: String?, modifier: Modifier, radius: androidx.compose.ui.unit.Dp, alpha: Float) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val req = remember(url) {
        coil.request.ImageRequest.Builder(ctx)
            .data(url)
            .size(48, 64)
            .memoryCacheKey("blur:$url")
            .build()
    }
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        AsyncImage(req, null, modifier.blur((radius / 2).coerceAtMost(12.dp)), contentScale = ContentScale.Crop, alpha = alpha)
    } else {
        AsyncImage(req, null, modifier, contentScale = ContentScale.Crop, alpha = alpha, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    }
}

/** 「筛选」 and the channel chevron open the same 频道 sheet. */
private val HomeChannelSheetOpen = mutableStateOf(false)

/** Sub-tab chosen from the 频道 sheet, per home page label. */
object HomeSubSel { val map = androidx.compose.runtime.mutableStateMapOf<String, Int>() }
/** One-shot request from another tab (我的 → Telegram 频道 / 漫画) to show a home source page. */
object HomeJump { var label by androidx.compose.runtime.mutableStateOf<String?>(null) }

/** iOS HomeChannelSheet: 「频道」 title + 完成, one section per page with its sub-channels as a 3-column grid of chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSheet(pages: List<Any>, labels: List<String>, current: Int, onDismiss: () -> Unit, onPick: (Int, Int?) -> Unit) {
    val c = IdjThemeColors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = c.surface, dragHandle = { BottomSheetDefaults.DragHandle(color = c.text.copy(0.25f)) }) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("频道", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).glassPill(RoundedCornerShape(50)).pressClick(label = "完成", ring = RoundedCornerShape(50), onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("完成", color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pages.forEachIndexed { pi, p ->
                val subs: List<String> = when (p) {
                    is HomeSource -> when (p) {
                        HomeSource.huangguo -> listOf("视频站", "App 源")
                        HomeSource.ai -> p.subs().map { it.subLabel } + if (IdjApp.instance.cms.adultUnlocked) listOf("黄果 AI 站") else emptyList()
                        HomeSource.channels, HomeSource.manga -> emptyList()
                        else -> p.subs().map { it.subLabel }
                    }
                    else -> listOf(labels[pi])
                }
                if (subs.isEmpty()) return@forEachIndexed
                Text(labels[pi], color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                subs.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { j, t ->
                            val si = subs.indexOf(t)
                            val on = pi == current && (HomeSubSel.map[labels[pi]] ?: 0) == si
                            Box(Modifier.weight(1f).height(44.dp).glassPill(RoundedCornerShape(10.dp), selected = on)
                                    .pressClick(label = t, ring = RoundedCornerShape(10.dp)) { onPick(pi, if (p is HomeSource && p != HomeSource.huangguo) si else null) }, contentAlignment = Alignment.Center) {
                                Text(t, color = if (on) c.accent else c.text, fontSize = 15.sp, maxLines = 1)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** B-style capsule channel bar: glass pills, the selected one lit white (dark) / ink (light); selected auto-centred. */
@Composable
private fun ChannelBar(items: List<String>, selected: Int, ink: Color, onMore: () -> Unit = {}, onSelect: (Int) -> Unit) {
    val c = IdjThemeColors
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        if (item != null) {
            val center = (info.viewportEndOffset - info.viewportStartOffset) / 2
            state.animateScrollBy((item.offset + item.size / 2 - center).toFloat())
        } else state.animateScrollToItem(selected)
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(state = state, modifier = Modifier.weight(1f).fadeEnd(0.9f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(items) { i, label ->
                val on = i == selected
                val fill = when {
                    c.dark && on -> Color.White.copy(0.22f)
                    c.dark -> Color.White.copy(0.07f)
                    on -> c.text
                    else -> c.glass
                }
                val line = when {
                    c.dark && on -> Color.White.copy(0.55f)
                    c.dark -> Color.White.copy(0.10f)
                    else -> Color.Transparent
                }
                val fg = when {
                    c.dark -> if (on) Color.White else Color.White.copy(0.70f)
                    on -> c.bg
                    else -> c.secondary
                }
                Box(Modifier.height(40.dp).clip(CircleShape).background(fill).border(1.dp, line, CircleShape)
                    .pressClick(0.97f, label = "home.channel.$label", ring = CircleShape, ringColor = if (c.dark) Color.White else null) { onSelect(i) }
                    .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = fg, fontSize = 16.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1)
                }
            }
            item { Spacer(Modifier.width(6.dp)) }
        }
        Box(Modifier.padding(start = 6.dp, end = 16.dp).size(40.dp).clip(CircleShape)
            .background(if (c.dark) Color.White.copy(0.07f) else c.glass)
            .pressClick(label = "展开全部频道", ring = CircleShape, ringColor = if (c.dark) Color.White else null, onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.KeyboardArrowDown, "展开全部频道", tint = if (c.dark) Color.White.copy(0.85f) else c.secondary)
        }
    }
}


@Composable
private fun FeedPage(source: HomeSource, onOpen: (String) -> Unit) {
    val subs = source.subs()
    var tab by remember { mutableStateOf(subs.getOrElse(HomeSubSel.map[source.label] ?: 0) { subs.first() }) }
    LaunchedEffect(HomeSubSel.map[source.label]) { HomeSubSel.map[source.label]?.let { i -> subs.getOrNull(i)?.let { tab = it } } }
    val boards = (tab as? HomeTab.Hongguo)?.g?.let { g -> g.subs.map { BoardSpec(g, it) } } ?: emptyList()
    val heroPicks = boards.firstOrNull()?.let { BoardCache.items[it.id] }?.take(5) ?: emptyList()
    var heroIndex by remember(tab) { mutableIntStateOf(0) }
    LaunchedEffect(tab) { boards.firstOrNull()?.let { BoardCache.load(it) } }

    @Composable fun subTabs(modifier: Modifier = Modifier) {
        SegmentedTabs(subs, tab, { it.subLabel }, {
            tab = it
            HomeSubSel.map[source.label] = subs.indexOf(it)
        }, modifier)
    }
    if (tab == HomeTab.ActorRank) {
        app.iduanju.ui.profile.ActorRankList(bottomPadding = 96.dp) { subTabs() }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + 72.dp)) {
        if (subs.size > 1) subTabs(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
        Column(Modifier.padding(top = 0.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            if (heroPicks.isNotEmpty()) {
                PosterHero(boards.first().title, heroPicks, heroIndex, { heroIndex = it }, onOpen)
            } else HeroSkeleton()
            val cw = IdjApp.instance.library.continueWatching
            if (source == HomeSource.hongguo && cw.isNotEmpty()) ContinueRow(cw, onOpen, whiteRing = true)
            val heroIds = heroPicks.map { it.seriesId }.toSet()
            val go = app.iduanju.ui.LocalNavigate.current
            val shelfCount = LocalLayoutMetrics.current.shelfItemCount
            boards.forEachIndexed { n, b ->
                LaunchedEffect(b.id) { BoardCache.load(b) }
                val items = BoardCache.items[b.id]
                val rest = if (n == 0) items?.filter { it.seriesId !in heroIds } else items
                HomeSection(b.title, if (BoardCache.failed.containsKey(b.id) && items == null) "加载失败，下拉重试" else null, rest?.take(shelfCount), onOpen) { go("board/${b.genre.name}/${b.sub.name}") }
            }
        }
    }
}

@Composable
private fun HeroSkeleton() {
    val c = IdjThemeColors
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(228.dp).clip(RoundedCornerShape(22.dp)).background(c.surface))
}

/** Spec 10/11: intrinsic-height poster/info grid. Android font metrics must never
 * consume the footer's measurement budget (a constrained Column shrank the CTA).
 * Measure all real pager titles first so every page shares one sufficient height. */
@Composable
private fun PosterHero(board: String, picks: List<HGSeries>, index: Int, setIndex: (Int) -> Unit, onOpen: (String) -> Unit) {
    val pager = rememberPagerState { picks.size }
    LaunchedEffect(pager.currentPage) { setIndex(pager.currentPage) }
    // Keyed on settledPage: currentPage flips halfway through the animation, which would restart this
    // effect and cancel the scroll, leaving the hero stuck between two pages.
    LaunchedEffect(pager.settledPage, picks.size) {
        if (picks.size > 1) { delay(6000); if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % picks.size) }
    }
    val cur = picks.getOrNull(index)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val kickerStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
    val titleStyle = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
    val metaStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
    // Material Text merges the ambient style (including letter spacing). Measure
    // that same resolved style, not a partial style that underestimates the label.
    val buttonStyle = LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(
        fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
    val metrics = LocalLayoutMetrics.current
    val scope = rememberCoroutineScope()
    // Tablet: the hero card takes ~62 % and a 「热播速览」 stack fills the rest, so a wide window never shows one
    // small poster in front of an empty dark gradient.
    val withSide = metrics.tier != SizeTier.Compact && picks.size > 1
    BoxWithConstraints(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        val sideGap = 14.dp
        val heroWidth = if (withSide) (maxWidth - sideGap) * 0.62f else maxWidth
        // Preserve the spec's 112 dp poster on phones; B-style banner: a larger 148 dp poster on big screens.
        val posterMax = if (metrics.tier == SizeTier.Compact) 112.dp else 148.dp
        val posterWidth = minOf(posterMax, (heroWidth - 28.dp - 14.dp - 160.dp).coerceAtLeast(80.dp))
        val infoWidth = heroWidth - 28.dp - posterWidth - 14.dp
        val infoPx = with(density) { infoWidth.roundToPx() }
        fun textHeight(text: String, style: androidx.compose.ui.text.TextStyle, lines: Int) = with(density) {
            measurer.measure(text, style, maxLines = lines, overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = infoPx)).size.height.toDp()
        }
        val ctaLabel = "查看详情"
        val pillPadding = 14.dp
        val iconSize = 14.dp
        val iconGap = 6.dp
        val ctaWidth = with(density) {
            val labelWidth = measurer.measure(ctaLabel, buttonStyle, maxLines = 1, softWrap = false).size.width
            (labelWidth + 2 * pillPadding.roundToPx() + iconSize.roundToPx() + iconGap.roundToPx()).toDp()
        }
        val visualButtonHeight = with(density) { 16.sp.toDp() + 12.dp }.coerceAtLeast(32.dp)
        val touchHeight = maxOf(48.dp, visualButtonHeight)
        val dotsWidth = if (picks.size > 1) (14 + (picks.size - 1) * 9).dp else 0.dp
        val stackedFooter = picks.size > 1 && infoWidth < ctaWidth + dotsWidth + 8.dp
        val footerHeight = touchHeight + if (stackedFooter) 12.dp else 0.dp
        fun kicker(i: Int) = if (board.isEmpty()) "正在热播 · TOP ${i + 1}" else "正在热播 · $board"
        fun metadata(s: HGSeries) = listOf(
            listOfNotNull(s.tags.take(2).joinToString(" · ").ifEmpty { null }, if (s.episodeCount > 0) "${s.episodeCount} 集" else null).joinToString(" · "),
            if (s.playCount > 0) DS.count(s.playCount) + "热度" else ""
        ).filter { it.isNotEmpty() }
        val infoHeight = picks.mapIndexed { i, s ->
            4.dp + textHeight(kicker(i), kickerStyle, 1) + 6.dp + textHeight(s.title, titleStyle, 2) +
                (if (metadata(s).isEmpty()) 0.dp else 7.dp + metadata(s).map { textHeight(it, metaStyle, 1) }.fold(0.dp) { a, b -> a + b }) +
                (if (s.playCount > 0) 38.dp else 0.dp) +
                8.dp + footerHeight
        }.maxOrNull() ?: 149.dp
        val cardHeight = maxOf(posterWidth * 4 / 3, infoHeight) + 28.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sideGap)) {
        Box(Modifier.width(heroWidth).height(cardHeight)
            .shadow(15.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(0.22f), spotColor = Color.Black.copy(0.22f))
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.22f), Color.White.copy(0.06f))), RoundedCornerShape(28.dp))) {
            androidx.compose.animation.Crossfade(cur?.cover, label = "heroBg") { url ->
                BlurBackdrop(url, Modifier.fillMaxSize().scale(1.5f), 40.dp, 0.85f)
            }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Black.copy(0.10f), Color.Black.copy(0.55f)))))
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("home.hero.pager")) { i ->
                val s = picks[i]
                Row(Modifier.fillMaxSize().testTag("home.hero.page.$i").graphicsLayer { alpha = 1f - kotlin.math.abs(pager.getOffsetDistanceInPages(i)).coerceIn(0f, 1f) * 1.6f }
                    .pressClick(0.98f, label = "home.hero.page.$i", ring = RoundedCornerShape(28.dp)) {
                        app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                        onOpen(s.seriesId)
                    }.padding(14.dp), verticalAlignment = Alignment.Top) {
                    PosterArt(s.cover, Modifier.size(posterWidth, posterWidth * 4 / 3), badge = HomeFmt.progress(s)?.let { DS.spaced(it) })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = 3.dp, bottom = 1.dp)) {
                        Text(kicker(i), Modifier.testTag("home.hero.kicker.$i"), color = Color(0xFFF2B55C), style = kickerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                            Text(s.title, Modifier.weight(1f).testTag("home.hero.title.$i"), color = Color.White, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (s.playCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.padding(top = 5.dp).clip(RoundedCornerShape(6.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF4D4F), Color(0xFFF5222D))))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    Text("热", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                        metadata(s).forEachIndexed { j, line ->
                            Text(line, Modifier.padding(top = if (j == 0) 7.dp else 0.dp).testTag("home.hero.meta.$i.$j"),
                                color = Color.White.copy(0.74f), style = metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.playCount > 0) {
                            // B-style bar, but honest data: relative heat within this hero group (no watch progress exists for rank picks).
                            val maxHeat = picks.maxOf { it.playCount }.coerceAtLeast(1)
                            Column(Modifier.padding(top = 9.dp)) {
                                Text("${DS.count(s.playCount)} 热度", color = Color.White.copy(0.74f), style = metaStyle, maxLines = 1)
                                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(0.15f))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth((s.playCount.toFloat() / maxHeat).coerceIn(0f, 1f)).clip(CircleShape)
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF4A90FF), Color(0xFF7B4DFF)))))
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                        @Composable fun cta() {
                            // Explicit 48 dp hit slot; the spec's 32 dp white pill stays visually compact.
                            Box(Modifier.height(touchHeight).width(ctaWidth).testTag("home.hero.cta.$i")
                                .pressClick(0.98f, label = ctaLabel) { onOpen(s.seriesId) }, contentAlignment = Alignment.Center) {
                                // B-style bright white pill over the blurred hero.
                                Row(Modifier.fillMaxWidth().height(visualButtonHeight).testTag("home.hero.pill.$i")
                                    .clip(CircleShape).background(Color.White)
                                    .padding(horizontal = pillPadding),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(iconGap)) {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(iconSize), tint = Color(0xFF14161F))
                                    Text(ctaLabel, Modifier.weight(1f).testTag("home.hero.ctaText.$i"), color = Color(0xFF14161F), style = buttonStyle, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        @Composable fun dots() {
                            if (picks.size > 1) Row(Modifier.testTag("home.hero.dots.$i"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                picks.indices.forEach { n ->
                                    val w by animateDpAsState(if (n == index) 14.dp else 5.dp, DS.select(), label = "dot")
                                    Box(Modifier.size(w, 5.dp).clip(RoundedCornerShape(50)).background(if (n == index) Color.White else Color.White.copy(0.4f)))
                                }
                            }
                        }
                        if (stackedFooter) Column(Modifier.fillMaxWidth().height(footerHeight)) {
                            cta()
                            Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterEnd) { dots() }
                        } else Row(Modifier.fillMaxWidth().height(footerHeight), verticalAlignment = Alignment.CenterVertically) {
                            cta(); Spacer(Modifier.weight(1f)); dots()
                        }
                    }
                }
            }
        }
        if (withSide) HeroSideStack(board, picks, index, Modifier.weight(1f).height(cardHeight), onOpen) { i ->
            scope.launch { pager.animateScrollToPage(i) }
        }
        }
    }
}

/**
 * Tablet-only 「热播速览」 companion to the hero: the other picks of the same board as compact rows on the same
 * blurred-cover material, so the hero row is balanced edge to edge. Tapping a row focuses it in the hero; the
 * round arrow opens its detail page.
 */
@Composable
private fun HeroSideStack(board: String, picks: List<HGSeries>, current: Int, modifier: Modifier, onOpen: (String) -> Unit, onFocus: (Int) -> Unit) {
    val others = picks.indices.filter { it != current }.take(3)
    Box(modifier
        .shadow(15.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(0.22f), spotColor = Color.Black.copy(0.22f))
        .clip(RoundedCornerShape(22.dp)).background(Color(0xFF1B1440))) {
        androidx.compose.animation.Crossfade(picks.getOrNull(others.firstOrNull() ?: 0)?.cover, label = "heroSideBg") { url ->
            BlurBackdrop(url, Modifier.fillMaxSize().scale(1.6f), 40.dp, 0.7f)
        }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Black.copy(0.42f), Color.Black.copy(0.70f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("热播速览", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                if (board.isNotEmpty()) Text(board, color = Color.White.copy(0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
                others.forEach { i ->
                    val s = picks[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .pressClick(0.98f, label = "home.hero.side.$i") { onFocus(i) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(0.1f))) {
                            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${i + 1}", color = Color(0xFFF2B55C), fontSize = 16.sp, fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = homeShelfMeta(s)
                            if (meta.isNotEmpty()) Text(meta, color = Color.White.copy(0.62f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.14f))
                            .pressClick(label = "打开 ${s.title}") { onOpen(s.seriesId) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(17.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(0.62f)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** ui-redesign 10/11 `.hs`: section header + one horizontal row of 112 dp cells. */
@Composable
private fun HomeSection(title: String, note: String?, list: List<HGSeries>?, onOpen: (String) -> Unit, onMore: () -> Unit) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    // Tablet: larger cells and up to 16 items so a 1100 dp shelf fills edge to edge instead of stopping at 60 %.
    val cell = metrics.shelfCardWidth
    val gap = if (metrics.tier == SizeTier.Compact) 10.dp else 14.dp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader2(title, note) { MoreLink(onClick = onMore) }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (list == null) items(if (metrics.tier == SizeTier.Compact) 4 else 9) {
                Box(Modifier.width(cell).glassSurface(RoundedCornerShape(18.dp), shadowRadius = 0.dp).padding(8.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(c.glass))
                        Box(Modifier.padding(top = 8.dp).width(80.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c.glass))
                    }
                }
            } else items(list, key = { it.seriesId }) { s ->
                HomeGlassPosterCell(s, homeShelfMeta(s), onOpen, Modifier.width(cell))
            }
        }
    }
}

/** B-style shelf cell: poster + title + meta inside one glass card; focus scales to 1.09 with a white glowing rim. */
@Composable
private fun HomeGlassPosterCell(s: HGSeries, subtitle: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusEvent { focused = it.hasFocus }
        .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = Color.White.copy(0.35f), spotColor = Color.White.copy(0.35f)) else Modifier)) {
        Column(Modifier.glassSurface(RoundedCornerShape(18.dp), shadowRadius = 10.dp)
            .pressClick(0.98f, label = s.title, ring = RoundedCornerShape(18.dp), ringColor = Color.White, focusScale = 1.09f) {
                app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                onOpen(s.seriesId)
            }.padding(8.dp)) {
            PosterArt(s.cover, Modifier.fillMaxWidth().aspectRatio(3f / 4f), corner = 12.dp, badge = HomeFmt.progress(s)?.let { DS.spaced(it) })
            Text(s.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            val sub = subtitle ?: if (s.playCount > 0) DS.count(s.playCount) + "热度" else HomeFmt.meta(s)
            if (sub.isNotEmpty()) Text(sub, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "悬疑 · 96 集". */
private fun homeShelfMeta(s: HGSeries): String {
    val parts = listOfNotNull(s.tags.firstOrNull(), if (s.episodeCount > 0) "${s.episodeCount} 集" else null)
    return if (parts.isEmpty()) (if (s.playCount > 0) DS.count(s.playCount) + "热度" else HomeFmt.meta(s)) else parts.joinToString(" · ")
}

/** iOS HomePosterCard: 3:4 poster r10 + 0.6 border, 13 semibold 2 lines, 11 meta. */
@Composable
fun HomePosterCard(s: HGSeries, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Column(Modifier.pressClick(0.97f, label = s.title, ring = RoundedCornerShape(10.dp)) {
        app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
        onOpen(s.seriesId)
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(c.elevated).border(0.6.dp, c.text.copy(0.12f), RoundedCornerShape(10.dp))) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            HomeFmt.progress(s)?.let { HomeBadge(it, Modifier.align(Alignment.BottomEnd).padding(5.dp)) }
        }
        Text(s.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp))
        val meta = HomeFmt.meta(s)
        if (meta.isNotEmpty()) Text(meta, color = c.secondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PosterCard(s: HGSeries, onOpen: (String) -> Unit) = HomePosterCard(s, onOpen)

/** ui-redesign 10 `.cw`: 296 dp cards — cover, title, 看到第 N 集 · 共 M 集, progress, round play button. */
@Composable
internal fun ContinueRow(list: List<app.iduanju.data.WatchProgress>, onOpen: (String) -> Unit, onPlay: ((app.iduanju.data.WatchProgress) -> Unit)? = null, whiteRing: Boolean = false) {
    val c = IdjThemeColors
    val go = app.iduanju.ui.LocalNavigate.current
    val metrics = LocalLayoutMetrics.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader2("继续观看") { MoreLink { go("library/history") } }
        // A full shelf of recent dramas; tablets get 280 dp cards with a wider gap.
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.tier == SizeTier.Compact) 10.dp else 14.dp)) {
            items(list.take(10), key = { it.seriesId }) { p ->
                Row(Modifier.width(metrics.continueCardWidth).glassSurface(RoundedCornerShape(18.dp), shadowRadius = 12.dp)
                    .pressClick(0.98f, label = p.title, ring = RoundedCornerShape(18.dp), ringColor = if (whiteRing) Color.White else null) { onOpen(p.seriesId) }
                    .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp, 69.dp).clip(RoundedCornerShape(8.dp)).background(c.glass)) {
                        AsyncImage(p.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(DS.spaced(p.resumeLabel), color = c.secondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                        DSProgressBar(p.fraction, Modifier.padding(top = 9.dp))
                    }
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c.text).pressClick(label = "继续播放 ${p.title}", ring = CircleShape, ringColor = if (whiteRing) Color.White else null) { if (onPlay != null) onPlay(p) else go("player/${p.seriesId}/${p.episodeIndex}") },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp).offset(x = 1.dp), tint = c.bg)
                    }
                }
            }
        }
    }
}
