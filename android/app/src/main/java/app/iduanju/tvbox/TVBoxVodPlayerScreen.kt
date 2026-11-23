package app.iduanju.tvbox

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.iduanju.IdjApp
import app.iduanju.core.cms.MacCMS
import app.iduanju.core.hongguo.HGSeries
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.hongguo.HGStream
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 04 · TVBox 点播播放页（16:9 安静播控 + 线路直链切换 + 选集网格 + 跨站换源）
 */
@OptIn(UnstableApi::class)
@Composable
fun TVBoxVodPlayerScreen(
    siteKey: String,
    seriesId: String,
    initialTitle: String,
    initialCover: String,
    startEp: Int,
    onBack: () -> Unit,
    onSwitchSiteSeries: (newSiteKey: String, newSeriesId: String, title: String, cover: String) -> Unit,
) {
    val c = IdjThemeColors
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val store = IdjApp.instance.tvbox
    val site = remember(siteKey) { store.findSite(siteKey) }

    var detail by remember(siteKey, seriesId) { mutableStateOf<HGSeriesDetail?>(null) }
    var loading by remember(siteKey, seriesId) { mutableStateOf(true) }
    var error by remember(siteKey, seriesId) { mutableStateOf<String?>(null) }
    var epIndex by remember(siteKey, seriesId) { mutableIntStateOf(startEp.coerceAtLeast(0)) }
    var lineIndex by remember(siteKey, seriesId, epIndex) { mutableIntStateOf(0) }
    var reverseOrder by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playError by remember { mutableStateOf<String?>(null) }

    // 跨站同片匹配结果
    var altMatches by remember(siteKey, seriesId) { mutableStateOf<List<Pair<TVBoxRepoSite, HGSeries>>>(emptyList()) }

    val exo = remember {
        val ok = OkHttpClient.Builder().followRedirects(true).build()
        val ds = OkHttpDataSource.Factory(ok).setUserAgent(MacCMS.UA)
        ExoPlayer.Builder(ctx, app.iduanju.ui.player.DecoderCaps.renderers(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = exo.duration.coerceAtLeast(0L)
                    playError = null
                }
            }
            override fun onPlayerError(err: PlaybackException) {
                playError = "线路播放失败：${err.errorCodeName}"
            }
        }
        exo.addListener(listener)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            exo.removeListener(listener)
            exo.release()
        }
    }

    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    LaunchedEffect(fullscreen) {
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity?.window?.let { w ->
            val ic = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
            ic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) {
                ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 拉取苹果 CMS 详情与全部直链线路
    LaunchedEffect(siteKey, seriesId) {
        if (site == null) {
            loading = false
            error = "未找到对应站点"
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val d = withContext(Dispatchers.IO) { MacCMS.detail(site.toCMSSite(), seriesId) }
            detail = d
            epIndex = startEp.coerceIn(0, (d.episodes.size - 1).coerceAtLeast(0))
        } catch (e: Exception) {
            error = e.message ?: "获取详情失败"
        } finally {
            loading = false
        }
    }

    // 跨站搜同片名，提供跨站换源
    val resolvedTitle = detail?.title?.takeIf { it.isNotBlank() } ?: initialTitle
    LaunchedEffect(resolvedTitle, siteKey) {
        val q = resolvedTitle.trim()
        val others = store.activeRepo?.sites?.filter { it.enabled && it.key != siteKey }.orEmpty().take(6)
        if (q.isEmpty() || others.isEmpty()) return@LaunchedEffect
        val matches = withContext(Dispatchers.IO) {
            coroutineScope {
                others.map { s ->
                    async {
                        runCatching {
                            val list = MacCMS.search(s.toCMSSite(), q, 1).items
                            val hit = list.firstOrNull { it.title.equals(q, ignoreCase = true) } ?: list.firstOrNull { it.title.contains(q, ignoreCase = true) }
                            hit?.let { s to it }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        }
        altMatches = matches
    }

    val currentEp = detail?.episodes?.getOrNull(epIndex)
    val streams: List<HGStream> = remember(currentEp) {
        currentEp?.let { MacCMS.streams(it) }.orEmpty()
    }
    val activeStream = streams.getOrNull(lineIndex) ?: streams.firstOrNull()

    // 切集 / 切线时加载播放流
    LaunchedEffect(activeStream?.url) {
        val u = activeStream?.url ?: return@LaunchedEffect
        playError = null
        exo.setMediaItem(MediaItem.fromUri(u))
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        exo.playWhenReady = true
    }

    // 定时同步进度 + 记录 TVBox 继续观看
    LaunchedEffect(detail, epIndex, activeStream?.url) {
        while (true) {
            positionMs = exo.currentPosition.coerceAtLeast(0L)
            durationMs = exo.duration.coerceAtLeast(0L)
            val d = detail
            val ep = currentEp
            if (d != null && ep != null && site != null && positionMs > 2000L) {
                store.recordWatch(
                    TVBoxRecentWatch(
                        siteKey = site.key,
                        siteName = site.name,
                        seriesId = seriesId,
                        title = d.title,
                        cover = d.cover.ifBlank { initialCover },
                        episodeIndex = epIndex,
                        episodeTitle = ep.title ?: "第 ${epIndex + 1} 集",
                        lineName = activeStream?.quality?.substringBefore(" · ") ?: "",
                        positionMs = positionMs,
                        durationMs = durationMs,
                    ),
                )
            }
            delay(1000L)
        }
    }

    Column(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else c.bg)) {
        // 顶部 16:9 / 全屏播放舞台
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.statusBarsPadding().aspectRatio(16f / 9.6f))
                .background(Color.Black)
                .clickable { controlsVisible = !controlsVisible },
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = exo
                    }
                },
                update = { it.player = exo },
                modifier = Modifier.fillMaxSize(),
            )

            if (buffering || loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = Color.White, strokeWidth = 2.4.dp)
            }

            if (playError != null) {
                Text(
                    playError!!,
                    color = Color(0xFFF2B55C),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xBB0A0C10))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            if (controlsVisible) {
                // 顶栏（全屏时避开状态栏与挖孔区域，并拦截空白点击防止误关播控）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(Color(0x66000000))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .then(if (fullscreen) Modifier.statusBarsPadding().displayCutoutPadding() else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x88101218))
                            .clickable { if (fullscreen) fullscreen = false else onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0x77000000))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            activeStream?.quality ?: "HLS · 直链",
                            color = Color(0xFFEEF0F3),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                // 中央播控
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                        Icon(Icons.Filled.Replay10, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0x9914161C))
                            .border(1.dp, Color.White.copy(0.22f), CircleShape)
                            .clickable { if (exo.isPlaying) exo.pause() else exo.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = { exo.seekTo(exo.currentPosition + 10_000L) }) {
                        Icon(Icons.Filled.Forward10, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }

                // 底部进度条与全屏按钮
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Color(0x80000000))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .then(if (fullscreen) Modifier.navigationBarsPadding().displayCutoutPadding() else Modifier)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fmtMs(positionMs), color = Color.White, fontSize = 11.sp)
                    Slider(
                        value = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac -> if (durationMs > 0L) exo.seekTo((frac * durationMs).toLong()) },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.28f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(24.dp),
                    )
                    Text(fmtMs(durationMs), color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { fullscreen = !fullscreen }, modifier = Modifier.size(30.dp)) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 下半部分：开放画布（标题 + 线路切换 + 选集网格 + 跨站换源）
        if (!fullscreen) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(resolvedTitle, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${currentEp?.title ?: ""} · ${site?.name ?: "TVBox"}${detail?.tags?.firstOrNull()?.let { " · $it" } ?: ""}",
                                color = c.secondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        // 倍速胶囊
                        Box(
                            Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(c.text.copy(0.07f))
                                .border(0.7.dp, c.text.copy(0.11f), RoundedCornerShape(16.dp))
                                .clickable {
                                    speed = when (speed) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 1.0f
                                    }
                                    exo.setPlaybackSpeed(speed)
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${speed}×", color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (error != null) {
                    item {
                        Text(error!!, color = c.accentWarm, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }

                // 播放线路（vod_play_from 直链解析）
                if (streams.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("播放线路（直链解析）", color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${streams.size} 条可用", color = c.secondary, fontSize = 12.sp)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(streams) { idx, st ->
                                val on = idx == lineIndex
                                Row(
                                    Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (on) c.accent.copy(0.15f) else c.text.copy(0.065f))
                                        .border(0.8.dp, if (on) c.accent.copy(0.42f) else c.text.copy(0.08f), RoundedCornerShape(10.dp))
                                        .clickable { lineIndex = idx }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Color(0xFF5FD08A) else c.secondary.copy(0.5f)))
                                    Text(st.quality ?: "线路 ${idx + 1}", color = if (on) c.accent else c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // 选集网格（4 列）
                val eps = detail?.episodes.orEmpty()
                if (eps.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("选集 · 共 ${eps.size} 集", color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row(
                                Modifier.clickable { reverseOrder = !reverseOrder },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (reverseOrder) "倒序" else "正序", color = c.secondary, fontSize = 12.sp)
                                Icon(Icons.Filled.SwapVert, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        val ordered = if (reverseOrder) eps.indices.reversed().toList() else eps.indices.toList()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ordered.chunked(4).forEach { rowIndices ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowIndices.forEach { realIdx ->
                                        val on = realIdx == epIndex
                                        val ep = eps[realIdx]
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (on) c.accent.copy(0.16f) else c.text.copy(0.065f))
                                                .border(0.8.dp, if (on) c.accent.copy(0.42f) else c.text.copy(0.08f), RoundedCornerShape(10.dp))
                                                .clickable { epIndex = realIdx; lineIndex = 0 }
                                                .padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                ep.title ?: "第 ${realIdx + 1} 集",
                                                color = if (on) c.accent else c.text,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    repeat(4 - rowIndices.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                // 跨站换源（同片匹配）
                if (altMatches.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("跨站换源（同片匹配）", color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${altMatches.size} 个可用站点", color = c.secondary, fontSize = 12.sp)
                        }
                        altMatches.forEach { (altSite, hit) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitchSiteSeries(altSite.key, hit.seriesId, hit.title, hit.cover) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF5FD08A)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(altSite.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    val ping = if (altSite.latencyMs in 1..9998) " · ${altSite.latencyMs}ms" else ""
                                    Text("${hit.subtitle ?: "可播放"}$ping", color = c.secondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box(
                                    Modifier
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(c.accent.copy(0.15f))
                                        .padding(horizontal = 11.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("切换", color = c.accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            HorizontalDivider(thickness = 0.6.dp, color = c.text.copy(0.07f))
                        }
                    }
                }
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
