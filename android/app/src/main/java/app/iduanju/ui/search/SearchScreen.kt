package app.iduanju.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.ui.components.GlassIconButton
import app.iduanju.ui.components.LocalLayoutMetrics
import app.iduanju.ui.components.pressClick
import app.iduanju.ui.components.SizeTier
import app.iduanju.ui.components.glassPill
import app.iduanju.ui.components.glassSurface
import app.iduanju.ui.home.HomeFmt
import app.iduanju.ui.home.HomePosterCard
import app.iduanju.ui.theme.IdjThemeColors
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object HotCache { var hot: List<HGSeries> = emptyList() }

/**
 * Unified standalone Search screen:
 * - Single top Liquid Glass search bar (`[←] [🔍 input ⊗] [搜索]`) — no duplicate bottom dock search bar.
 * - Multi-source selector strip (`红果` · `黄果` [18+] · `采集站` · `插件`) always visible under the bar when multiple
 *   sources are enabled, dynamically updating on 18+ / plugin / CMS changes.
 * - Clean 3-state body: Landing (history chips, 2-col discovery, Top-3 + ranked hot board), Live Suggestions, Results.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    initialQuery: String = "",
    onBack: (() -> Unit)? = null,
) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    val ia = IdjApp.instance
    val lib = ia.library
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf(initialQuery) }
    var submitted by remember { mutableStateOf("") }
    var hot by remember { mutableStateOf(HotCache.hot) }
    var hotErr by remember { mutableStateOf<String?>(null) }
    var seed by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }

    val items = remember { mutableStateListOf<HGSeries>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var verify by remember { mutableStateOf<app.iduanju.core.util.VerificationRequiredException?>(null) }
    var searchGen by remember { mutableIntStateOf(0) }

    // Reactive multi-source list: [红果, 黄果 (when adultUnlocked)] + enabled CMS sites + enabled video/shortdrama plugins
    val adult = ia.cms.adultUnlocked
    val sources: List<Pair<String, String>> =
        listOf("hongguo" to "红果") +
            (if (adult) listOf("huangguo" to "黄果") else emptyList()) +
            ia.cms.visibleSites.map { "cms:" + it.key to it.name } +
            ia.plugins.visible(adult).filter { it.type == "video" || it.type == "shortdrama" }.map { "plugin:" + it.id to it.name }

    var source by remember { mutableStateOf("hongguo") }
    var pageNo by remember { mutableIntStateOf(1) }
    val currentSourceLabel = sources.firstOrNull { it.first == source }?.second ?: "红果"

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank()) {
            delay(80)
            runCatching { focusRequester.requestFocus(); keyboard?.show() }
        }
        if (hot.isEmpty()) {
            try {
                hot = withContext(Dispatchers.IO) { ia.hongguo.rank(HGRankSub.hotSearch, HGRankGenre.all).items.take(23) }
                HotCache.hot = hot
            } catch (e: Exception) {
                hotErr = e.message
            }
        }
    }

    fun loadMore(reset: Boolean) {
        if (loading && !reset) return
        val q = submitted
        if (q.isEmpty()) return
        val myGen = if (reset) ++searchGen else searchGen
        loading = true
        err = null
        verify = null
        scope.launch {
            try {
                if (reset) pageNo = 1
                val reqPage = pageNo
                val src = source
                val p = withContext(Dispatchers.IO) {
                    when {
                        src == "hongguo" -> ia.hongguo.search(q, cursor = if (reset) null else cursor)
                        src == "huangguo" -> app.iduanju.core.hongguo.HGPage(ia.huangguo.search(q), false)
                        src.startsWith("cms:") -> ia.cms.site(src.removePrefix("cms:"))?.let {
                            app.iduanju.core.cms.MacCMS.search(it, q, reqPage)
                        } ?: app.iduanju.core.hongguo.HGPage(emptyList(), false)
                        else -> try {
                            ia.plugins.video(src.removePrefix("plugin:")).search(q, reqPage)
                        } catch (e: app.iduanju.core.plugin.PluginException) {
                            throw Exception(e.friendly)
                        }
                    }
                }
                if (myGen != searchGen) return@launch
                pageNo = reqPage + 1
                if (reset) items.clear()
                val seen = items.map { it.seriesId }.toSet()
                items.addAll(p.items.filter { it.seriesId !in seen })
                cursor = p.nextCursor
                hasMore = p.hasMore && p.items.isNotEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.iduanju.core.util.VerificationRequiredException) {
                if (myGen == searchGen) { verify = e; err = "需要验证" }
            } catch (e: Exception) {
                if (myGen == searchGen) err = e.message ?: "搜索失败"
            } finally {
                if (myGen == searchGen) loading = false
            }
        }
    }

    LaunchedEffect(sources) {
        if (sources.none { it.first == source }) {
            source = "hongguo"
            cursor = null
            items.clear()
            if (submitted.isNotEmpty()) loadMore(true)
        }
    }

    fun submit(q0: String = query) {
        val q = q0.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus()
        keyboard?.hide()
        query = q
        lib.addSearch(q)
        submitted = q
        cursor = null
        items.clear()
        loadMore(true)
    }

    fun clearQuery() {
        searchGen++
        loading = false
        query = ""
        submitted = ""
        cursor = null
        err = null
        verify = null
        items.clear()
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) submit(initialQuery)
    }

    BackHandler(enabled = submitted.isNotEmpty()) {
        clearQuery()
    }

    val bgColor = c.bg
    val searchBackdrop = rememberLayerBackdrop {
        drawRect(bgColor)
        drawContent()
    }
    val density = LocalDensity.current
    var topBarH by remember { mutableStateOf(58.dp) }
    val bottomInsetDp = with(density) {
        WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(this).toDp()
    }
    val bottomPad = bottomInsetDp + 24.dp

    Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        // Content layer recorded into backdrop for floating Liquid Glass header
        Box(Modifier.fillMaxSize().layerBackdrop(searchBackdrop)) {
            when {
                submitted.isNotEmpty() -> {
                    val grid = rememberLazyGridState()
                    LaunchedEffect(grid, items.size) {
                        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                            if (hasMore && !loading && last >= items.size - 4) loadMore(false)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(metrics.posterCols),
                        modifier = Modifier.fillMaxSize(),
                        state = grid,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topBarH + 8.dp, bottom = bottomPad),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items, key = { it.seriesId + "|" + it.title }) { s ->
                            HomePosterCard(s) { sid ->
                                focusManager.clearFocus()
                                onOpenDetail(sid)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                    verify != null -> app.iduanju.huangguo.VerifyRequiredState(verify!!) { loadMore(items.isEmpty()) }
                                    err != null -> Text("$err · 点击重试", color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable { loadMore(items.isEmpty()) })
                                    items.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("在「$currentSourceLabel」未找到「$submitted」", color = c.secondary, fontSize = 14.sp)
                                        if (sources.size > 1) {
                                            Text("可点击上方片源标签切换其他站源检索", color = c.tertiary, fontSize = 12.sp)
                                        }
                                    }
                                    !hasMore -> Text("没有更多了", color = c.tertiary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                else -> {
                    val typing = query.isNotBlank()
                    Crossfade(targetState = typing, label = "searchLandingOrSuggest") { isTyping ->
                        if (isTyping) {
                            SearchSuggestionsList(
                                query = query.trim(),
                                sourceLabel = currentSourceLabel,
                                history = lib.searchHistory,
                                hot = hot,
                                topPadding = topBarH,
                                bottomPadding = bottomPad,
                                onSubmit = { submit(it) },
                                onFill = { query = it },
                                onOpenDetail = { sid ->
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    onOpenDetail(sid)
                                },
                            )
                        } else {
                            SearchLandingContent(
                                history = lib.searchHistory,
                                editing = editing,
                                onEditToggle = { editing = it },
                                onClearHistory = { lib.clearSearch(); editing = false },
                                onRemoveHistory = { lib.removeSearch(it) },
                                hot = hot,
                                hotErr = hotErr,
                                seed = seed,
                                onRefreshSeed = { seed++ },
                                topPadding = topBarH,
                                bottomPadding = bottomPad,
                                onPick = { submit(it) },
                                onOpenDetail = { sid ->
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    onOpenDetail(sid)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Subtle gradient scrim beneath floating header
        Box(
            Modifier
                .fillMaxWidth()
                .height(topBarH + 14.dp)
                .background(
                    Brush.verticalGradient(
                        0f to c.bg.copy(alpha = 0.96f),
                        0.72f to c.bg.copy(alpha = 0.80f),
                        1f to c.bg.copy(alpha = 0f),
                    ),
                ),
        )

        // Unified Top Header: Search Input Capsule + Multi-Source Selector Strip
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { topBarH = with(density) { it.height.toDp() } },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = if (sources.size > 1) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onBack != null) {
                    GlassIconButton(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "返回",
                        backdrop = searchBackdrop,
                        size = 42.dp,
                        iconSize = 20.dp,
                        onClick = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onBack()
                        },
                    )
                }

                Row(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .glassSurface(RoundedCornerShape(50), backdrop = searchBackdrop, shadowRadius = 8.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                if (sources.size > 1) "搜索 $currentSourceLabel（共 ${sources.size} 个片源）" else "搜索短剧 / 演员",
                                color = c.secondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                if (it.isEmpty() && submitted.isNotEmpty()) clearQuery()
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = "清除",
                            tint = c.secondary,
                            modifier = Modifier.size(17.dp).clickable { clearQuery() },
                        )
                    }
                }

                val hasDraftChange = query.trim().isNotEmpty() && query.trim() != submitted
                if (submitted.isNotEmpty() && !hasDraftChange) {
                    Box(
                        Modifier
                            .height(38.dp)
                            .glassPill(RoundedCornerShape(50), selected = false)
                            .pressClick(label = "重置", ring = RoundedCornerShape(50)) { clearQuery() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("重置", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    val canSubmit = query.trim().isNotEmpty()
                    Box(
                        Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (canSubmit) c.accent else c.text.copy(alpha = 0.08f))
                            .pressClick(enabled = canSubmit, label = "搜索", ring = RoundedCornerShape(50)) { submit() }
                            .padding(horizontal = 15.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "搜索",
                            color = if (canSubmit) c.onAccent else c.secondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (sources.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    sources.forEach { (id, name) ->
                        val active = id == source
                        Box(
                            Modifier
                                .height(32.dp)
                                .glassPill(RoundedCornerShape(50), selected = active)
                                .pressClick(0.97f, ring = RoundedCornerShape(50)) {
                                    if (!active) {
                                        source = id
                                        cursor = null
                                        items.clear()
                                        if (submitted.isNotEmpty()) loadMore(true)
                                        else if (query.isNotBlank()) submit(query)
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                color = if (active) c.accent else c.secondary,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchLandingContent(
    history: List<String>,
    editing: Boolean,
    onEditToggle: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    hot: List<HGSeries>,
    hotErr: String?,
    seed: Int,
    onRefreshSeed: () -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
    onPick: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val c = IdjThemeColors
    val metrics = LocalLayoutMetrics.current
    val grid = rememberLazyGridState()
    val discover = remember(hot, seed) {
        if (hot.size > 3) hot.drop(3).map { it.title }.shuffled(java.util.Random(seed + 1L)).take(8) else emptyList()
    }
    val topCount = when (metrics.tier) {
        SizeTier.Compact -> 3
        SizeTier.Medium -> 4
        SizeTier.Expanded -> 5
    }
    val gap = if (metrics.tier == SizeTier.Compact) 8.dp else 12.dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(metrics.listCols),
        modifier = Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "history") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("历史搜索", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (editing) {
                            Text("清空", color = c.accentWarm, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onClearHistory))
                            Spacer(Modifier.width(14.dp))
                            Text("完成", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onEditToggle(false) })
                        } else {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "编辑历史",
                                tint = c.secondary,
                                modifier = Modifier.size(19.dp).clickable { onEditToggle(true) },
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        history.forEach { term ->
                            SearchChip(term, badge = null, removable = editing) {
                                if (editing) onRemoveHistory(term) else onPick(term)
                            }
                        }
                    }
                }
            }
        }

        if (discover.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "discover") {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("猜你想搜", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onRefreshSeed)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, null, tint = c.secondary, modifier = Modifier.size(15.dp))
                            Text("换一批", color = c.secondary, fontSize = 12.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        discover.chunked(2).forEachIndexed { rowIdx, pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEachIndexed { colIdx, title ->
                                    val idx = rowIdx * 2 + colIdx
                                    val badge = when (idx) { 0 -> "热"; 1 -> "荐"; else -> null }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(c.surface)
                                            .border(0.6.dp, c.hairline, RoundedCornerShape(12.dp))
                                            .clickable { onPick(title) }
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            title,
                                            color = c.text,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (badge != null) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                badge,
                                                color = c.accentWarm,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(c.accentWarm.copy(alpha = 0.14f))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "hotHeader") {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("热搜实时榜", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                when {
                    hot.isEmpty() && hotErr != null -> Text(hotErr, color = c.secondary, fontSize = 13.sp)
                    hot.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(topCount) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(0.62f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.elevated.copy(alpha = 0.6f)),
                            )
                        }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        hot.take(topCount).forEachIndexed { i, s ->
                            Box(Modifier.weight(1f)) {
                                TopRankCard(s, i + 1, onOpenDetail)
                            }
                        }
                    }
                }
            }
        }

        val rest = hot.drop(topCount)
        items(rest.size, key = { i -> "rank_${rest[i].seriesId}_$i" }) { i ->
            CompactRankRow(rest[i], i + topCount + 1, onOpenDetail)
        }
    }
}

@Composable
private fun SearchSuggestionsList(
    query: String,
    sourceLabel: String,
    history: List<String>,
    hot: List<HGSeries>,
    topPadding: Dp,
    bottomPadding: Dp,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val c = IdjThemeColors
    val histHits = remember(query, history) {
        history.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }.take(5)
    }
    val hotHits = remember(query, hot) {
        hot.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.tags.any { t -> t.contains(query, ignoreCase = true) }
        }.take(10)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding + 6.dp, bottom = bottomPadding),
    ) {
        item(key = "directSearch") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSubmit(query) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Search, null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "搜索「$query」",
                        color = c.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("当前片源：$sourceLabel", color = c.secondary, fontSize = 12.sp)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = c.hairline)
        }

        items(histHits, key = { "h_$it" }) { h ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSubmit(h) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.History, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                Text(h, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.NorthWest,
                    contentDescription = "填入搜索词",
                    tint = c.secondary,
                    modifier = Modifier.size(16.dp).clickable { onFill(h) },
                )
            }
        }

        if (hotHits.isNotEmpty()) {
            item(key = "hotSuggestTitle") {
                Text(
                    "热榜匹配",
                    color = c.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(hotHits, key = { "s_${it.seriesId}" }) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                            onOpenDetail(s.seriesId)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(42.dp, 56.dp).clip(RoundedCornerShape(6.dp)).background(c.elevated)) {
                        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(HomeFmt.meta(s), color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchChip(text: String, badge: String?, removable: Boolean, onClick: () -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.surface)
            .border(0.6.dp, c.hairline, RoundedCornerShape(50))
            .pressClick(label = "", ring = RoundedCornerShape(50), onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        if (badge != null) {
            Text(" · $badge", color = c.accentWarm, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (removable) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Filled.Cancel, null, tint = c.secondary, modifier = Modifier.size(13.dp))
        }
    }
}

private fun rankGradient(r: Int): List<Color> = when (r) {
    1 -> listOf(Color(0.95f, 0.25f, 0.3f), Color(0.75f, 0.1f, 0.2f))
    2 -> listOf(Color(0.25f, 0.35f, 0.95f), Color(0.15f, 0.2f, 0.7f))
    else -> listOf(Color(0.98f, 0.45f, 0.25f), Color(0.85f, 0.25f, 0.15f))
}

private fun rankColor(r: Int): Color = when (r) {
    1 -> Color(1f, 0.27f, 0.2f)
    2 -> Color(1f, 0.55f, 0.15f)
    3 -> Color(1f, 0.78f, 0.2f)
    else -> Color(0.8f, 0.15f, 0.25f)
}

@Composable
private fun TopRankCard(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.62f)
            .clip(RoundedCornerShape(12.dp))
            .pressClick(0.97f, label = s.title, ring = RoundedCornerShape(12.dp)) {
                app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                onOpen(s.seriesId)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val g = rankGradient(rank)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.62f to g[0], 1f to g[1])))
        Column(
            Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("TOP$rank", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(s.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeFmt.metaLine(s), color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CompactRankRow(s: HGSeries, rank: Int, onOpen: (String) -> Unit) {
    val c = IdjThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .pressClick(0.98f, label = s.title, ring = RoundedCornerShape(10.dp)) {
                app.iduanju.data.Catalog.rememberPreview(s.seriesId, s.title, s.cover)
                onOpen(s.seriesId)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(6.dp)).background(c.elevated)) {
            AsyncImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                "$rank",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(rankColor(rank))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.playCount > 0) Text(HomeFmt.count(s.playCount), color = Color(0.95f, 0.3f, 0.3f), fontSize = 12.sp)
                Text(if (s.episodeCount > 0) "${s.episodeCount}集" else "短剧", color = c.secondary, fontSize = 12.sp)
            }
        }
    }
}
