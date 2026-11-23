package app.iduanju.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.components.*
import app.iduanju.ui.theme.IdjThemeColors

/** Aligned with short-drama DetailScreen: blurred cover hero, single 3-column metrics strip, ink CTA + round actions, open hairline sections. */
@Composable
internal fun NovelBookDetail(
    book: NovelHit,
    info: NovelBookDetails?,
    sourceName: String,
    chapters: List<NovelChapter>,
    loading: Boolean,
    error: String?,
    onShelf: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    cachedCount: Int = 0,
    sourceCount: Int = 1,
    onBack: () -> Unit,
    onSwitch: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int?) -> Unit,
    onRetry: () -> Unit,
    mediaType: Int = 0,
) {
    val c = IdjThemeColors
    val title = info?.name.orEmpty().ifBlank { book.name }
    val author = info?.author.orEmpty().ifBlank { book.author }
    val kind = info?.kind.orEmpty().ifBlank { book.kind }
    val latest = info?.latest.orEmpty().ifBlank { book.latest }
    val intro = info?.intro.orEmpty().ifBlank { book.intro }.trim()
    val cover = info?.cover.orEmpty().ifBlank { book.cover }
    val displayBook = remember(book, title, author, cover) { book.copy(name = title, author = author, cover = cover) }

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val pinned by remember { derivedStateOf { scroll.value > with(density) { 210.dp.toPx() } } }
    var actionsBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val actionsThresholdPx = with(density) { 100.dp.toPx() }
    val actionsOut by remember(actionsThresholdPx) { derivedStateOf { actionsBottom < actionsThresholdPx } }
    val chapterCount = chapters.size
    val enabled = chapterCount > 0 && (onShelf || !loading)
    val media = mediaType == 1 || mediaType == 4
    val readLabel = when (mediaType) { 1 -> "开始收听"; 4 -> "开始观看"; else -> "开始阅读" }
    var sourceMenu by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(false) }
    var introHasOverflow by remember { mutableStateOf(false) }
    val tags = remember(kind) { kind.split('·', '/', ',', '，', '|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5) }

    Box(Modifier.fillMaxSize().background(c.bg).testTag("novel.detail")) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        ) {
            // 1. Blurred cover hero (exact sibling to DetailScreen DetailHero)
            Box(Modifier.fillMaxWidth().height(286.dp)) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    if (cover.isNotBlank()) {
                        NovelBlurredBackdrop(cover, Modifier.fillMaxSize(), radius = 30.dp)
                    } else {
                        val (c1, c2) = bookCoverBaseColor(title)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(0.32f),
                                0.36f to Color.Black.copy(0.10f),
                                0.78f to c.bg.copy(0.84f),
                                1f to c.bg,
                            )
                        ),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    NovelPoster(displayBook, Modifier.size(106.dp, 150.dp), corner = 11.dp)
                    Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
                        if (sourceName.isNotBlank()) {
                            Row(
                                Modifier
                                    .height(23.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(c.accentSoft)
                                    .pressClick(0.96f) { sourceMenu = true }
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    if (sourceCount > 1) "$sourceName · $sourceCount 源" else sourceName,
                                    color = c.accent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 175.dp),
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(13.dp), tint = c.accent)
                            }
                        }
                        Text(
                            title,
                            color = c.text,
                            fontSize = 23.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (author.isNotBlank() || tags.isNotEmpty()) {
                            Row(
                                Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (author.isNotBlank()) {
                                    Text(author, color = c.secondary, fontSize = 13.sp, maxLines = 1)
                                }
                                val kindText = tags.take(2).joinToString(" · ")
                                if (kindText.isNotBlank()) {
                                    Text(
                                        if (author.isNotBlank()) " · $kindText" else kindText,
                                        color = c.secondary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Single subtle 3-column metrics strip (real data only, mirrors short-drama Stats)
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .dsCard(18.dp)
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetricCell(
                    value = if (chapterCount > 0) "$chapterCount" else if (loading) "…" else "—",
                    unit = if (chapterCount > 0) "章" else "",
                    label = "目录总章",
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(26.dp).background(c.hairline))
                MetricCell(
                    value = "${sourceCount.coerceAtLeast(1)}",
                    unit = "个",
                    label = "可用书源",
                    modifier = Modifier.weight(1f).pressClick(0.96f) { sourceMenu = true },
                )
                Box(Modifier.width(1.dp).height(26.dp).background(c.hairline))
                MetricCell(
                    value = when {
                        mediaType == 1 -> "有声"
                        mediaType == 4 -> "视频"
                        else -> "$cachedCount"
                    },
                    unit = if (media) "" else "章",
                    label = if (media) "源类型" else "离线已缓",
                    modifier = Modifier.weight(1f),
                )
            }

            // 3. Primary action row (exact sibling to DetailScreen Play + Heart)
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 15.dp)
                    .onGloballyPositioned { actionsBottom = it.boundsInRoot().bottom },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PillButton(
                    title = readLabel,
                    modifier = Modifier.weight(1f).testTag("novel.detail.read"),
                    icon = Icons.Rounded.PlayArrow,
                    tone = PillTone.Ink,
                    height = 48.dp,
                    enabled = enabled,
                ) { onRead(null) }
                DetailRoundAction(
                    icon = if (onShelf) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                    label = if (onShelf) "已在书架" else "加入书架",
                    active = onShelf,
                    enabled = enabled && !onShelf && !media,
                    modifier = Modifier.testTag("novel.detail.add"),
                    onClick = onAdd,
                )
                DetailRoundAction(
                    icon = Icons.Rounded.Download,
                    label = "下载",
                    active = false,
                    enabled = enabled && !media,
                    modifier = Modifier.testTag("novel.detail.download"),
                    onClick = onDownload,
                )
            }

            if (downloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
                    color = c.accent,
                    trackColor = c.glassBorder,
                )
            }

            // 4. Open tags + folding synopsis (0 cards)
            if (tags.isNotEmpty()) {
                LazyRow(
                    Modifier.padding(top = 15.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(tags) { tag ->
                        Box(Modifier.height(24.dp).clip(CircleShape).background(c.glass).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(tag, color = c.secondary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (intro.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).animateContentSize(DS.panel())) {
                    Text(
                        intro,
                        color = c.secondary,
                        fontSize = 13.5.sp,
                        lineHeight = 22.sp,
                        maxLines = if (introExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!introExpanded) introHasOverflow = it.hasVisualOverflow },
                        modifier = Modifier.testTag("novel.detail.intro"),
                    )
                    if (introHasOverflow || introExpanded) {
                        Text(
                            if (introExpanded) "收起" else "展开",
                            color = c.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp).clickable { introExpanded = !introExpanded },
                        )
                    }
                }
            }

            // 5. Open hairline Directory & Source rows (0 cards)
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .pressClick(0.985f, enabled = enabled) { onRead(if (chapterCount > 0) chapterCount - 1 else null) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("目录", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (latest.isNotBlank()) "最新：$latest" else if (chapterCount > 0) chapters.last().title else "暂无章节信息",
                        color = c.secondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (chapterCount > 0) {
                        Text("共 $chapterCount 章", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = c.tertiary)
                }
                Hairline()
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .testTag("novel.detail.source")
                            .pressClick(0.985f) { sourceMenu = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("书源", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c.ok))
                            Text(sourceName, color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (sourceCount > 1) "换源 ($sourceCount)" else "换源", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = c.accent)
                    }
                    DropdownMenu(sourceMenu, { sourceMenu = false }) {
                        DropdownMenuItem(text = { Text(sourceName) }, onClick = { sourceMenu = false }, trailingIcon = { Icon(Icons.Outlined.Check, null) })
                        DropdownMenuItem(text = { Text("搜索其他书源") }, onClick = { sourceMenu = false; onSwitch() })
                    }
                }
                Hairline()
            }

            // 6. Open hairline Preview Chapters (0 cards)
            if (!media && chapters.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("试读章节", color = c.text, fontSize = 16.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("点击直接阅读", color = c.tertiary, fontSize = 12.sp)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    chapters.take(3).forEachIndexed { index, chapter ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 43.dp)
                                .pressClick(0.985f, enabled = enabled) { onRead(index) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(String.format("%02d", index + 1), color = c.tertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(20.dp))
                            Text(chapter.title, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (index == 0) DSStatus("试读", StatusTone.Accent)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = c.tertiary)
                        }
                    }
                }
            }

            if (loading) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Text("正在读取目录…", color = c.secondary, fontSize = 13.sp)
                }
            }
            if (error != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    MiniButton("重试", onRetry)
                }
            }
        }

        // Pinned translucent nav bar matching DetailScreen
        Box(Modifier.fillMaxWidth().dsPinned(pinned)) {
            DSNavBar(
                title = title,
                modifier = Modifier.statusBarsPadding().padding(bottom = 6.dp),
                titleAlpha = animateFloatAsState(if (pinned) 1f else 0f, label = "novelDetailNav").value,
                leading = { BackButton(if (pinned) Tone.Glass else Tone.Overlay, onBack) },
            )
        }

        // Floating glass bar when scrolled past hero actions (exact sibling to DetailScreen playbar)
        AnimatedVisibility(
            visible = enabled && actionsOut,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            enter = slideInVertically(DS.panel()) { it } + fadeIn(),
            exit = slideOutVertically(DS.panel()) { it } + fadeOut(),
        ) {
            Row(Modifier.fillMaxWidth().dsBar(32.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(readLabel, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, tone = PillTone.Ink, height = 46.dp, enabled = enabled) { onRead(null) }
                DetailRoundAction(
                    icon = if (onShelf) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                    label = if (onShelf) "已在书架" else "加入书架",
                    active = onShelf,
                    enabled = enabled && !onShelf && !media,
                    size = 46.dp,
                    onClick = onAdd,
                )
                DetailRoundAction(
                    icon = Icons.Rounded.Download,
                    label = "下载",
                    active = false,
                    enabled = enabled && !media,
                    size = 46.dp,
                    onClick = onDownload,
                )
            }
        }
    }
}

@Composable
private fun MetricCell(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    val c = IdjThemeColors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, color = c.secondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 1.dp))
        }
        Text(label, color = c.tertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DetailRoundAction(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val c = IdjThemeColors
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled || active) 1f else 0.42f }
            .clip(CircleShape)
            .background(if (active) c.accentSoft else c.glass)
            .border(1.dp, if (active) c.accentLine else c.hairline, CircleShape)
            .pressClick(0.95f, enabled = enabled, label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (active) c.accent else c.text)
    }
}
