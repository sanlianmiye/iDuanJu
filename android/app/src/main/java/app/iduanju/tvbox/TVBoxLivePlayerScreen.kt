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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import okhttp3.OkHttpClient

/**
 * 05 · TVBox 直播横屏播放页（安静播控 + 右侧半透明毛玻璃换台与信号源切换抽屉）
 */
@OptIn(UnstableApi::class)
@Composable
fun TVBoxLivePlayerScreen(
    repoId: String,
    liveId: String,
    startChannelIndex: Int,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val store = IdjApp.instance.tvbox

    val repo = remember(repoId) { store.repos.firstOrNull { it.id == repoId } ?: store.activeRepo }
    val live = remember(repo, liveId) { repo?.lives?.firstOrNull { it.id == liveId } }
    var channels by remember(repoId, liveId) { mutableStateOf(live?.channels.orEmpty()) }
    var channelIdx by remember(repoId, liveId) { mutableIntStateOf(startChannelIndex.coerceAtLeast(0)) }
    var lineIdx by remember(channelIdx) { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var playError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repoId, liveId) {
        if (channels.isEmpty() && repo != null && live != null) {
            channels = runCatching { store.loadLiveChannels(repo.id, live.id) }.getOrDefault(emptyList())
        }
    }

    val currentChannel = channels.getOrNull(channelIdx) ?: channels.firstOrNull()
    val groups = remember(channels) { listOf("全部", "收藏") + channels.map { it.group }.distinct() }
    var drawerGroup by remember(currentChannel?.group) { mutableStateOf(currentChannel?.group ?: "全部") }
    val drawerChannels = remember(channels, drawerGroup, store.favoriteChannels.toList()) {
        when (drawerGroup) {
            "全部" -> channels
            "收藏" -> channels.filter { store.isFavoriteChannel(it.name) }
            else -> channels.filter { it.group == drawerGroup }
        }
    }

    val exo = remember {
        val ok = OkHttpClient.Builder().followRedirects(true).build()
        val ds = OkHttpDataSource.Factory(ok).setUserAgent(MacCMS.UA)
        ExoPlayer.Builder(ctx, app.iduanju.ui.player.DecoderCaps.renderers(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
    }

    val isTablet = app.iduanju.ui.components.LocalLayoutMetrics.current.isTablet
    DisposableEffect(Unit) {
        if (!isTablet) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playError = null
            }
            override fun onPlayerError(error: PlaybackException) {
                playError = "信号源不可用（可点击下方切换备用线路）"
            }
        }
        exo.addListener(listener)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exo.removeListener(listener)
            exo.release()
        }
    }

    val streamUrl = currentChannel?.urls?.getOrNull(lineIdx) ?: currentChannel?.urls?.firstOrNull()
    LaunchedEffect(streamUrl) {
        val u = streamUrl ?: return@LaunchedEffect
        playError = null
        buffering = true
        exo.setMediaItem(MediaItem.fromUri(u))
        exo.prepare()
        exo.playWhenReady = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .clickable { controlsVisible = !controlsVisible },
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    this.resizeMode = resizeMode
                    player = exo
                }
            },
            update = {
                it.player = exo
                it.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.4.dp)
        }

        if (playError != null) {
            Text(
                playError!!,
                color = Color(0xFFF2B55C),
                fontSize = 12.5.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC0A0C10))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        // 左侧安静播控层
        if (controlsVisible) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(end = if (drawerOpen) 276.dp else 0.dp)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x8F101218))
                            .border(0.8.dp, Color.White.copy(0.16f), CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(currentChannel?.name ?: "直播频道", color = Color(0xFFEEF0F3), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF6B6B)))
                            Text(
                                "LIVE · 线路 ${lineIdx + 1}/${(currentChannel?.urls?.size ?: 1).coerceAtLeast(1)} · ${live?.name ?: ""}",
                                color = Color(0xB3EEF0F3),
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }

                // 中央暂停键
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0x94101218))
                            .border(1.dp, Color.White.copy(0.22f), CircleShape)
                            .clickable { if (exo.isPlaying) exo.pause() else exo.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }

                // 底部胶囊控制条
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LandControlPill(
                        label = "频道列表",
                        active = drawerOpen,
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    ) { drawerOpen = !drawerOpen }

                    val lineCount = (currentChannel?.urls?.size ?: 1).coerceAtLeast(1)
                    LandControlPill(label = "线路 ${lineIdx + 1}/$lineCount") {
                        lineIdx = (lineIdx + 1) % lineCount
                    }

                    val ratioLabel = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "满屏"
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "裁切"
                        else -> "画面 16:9"
                    }
                    LandControlPill(label = ratioLabel) {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }

                    val fav = currentChannel != null && store.isFavoriteChannel(currentChannel.name)
                    LandControlPill(
                        label = if (fav) "已收藏" else "收藏",
                        active = fav,
                        icon = if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    ) {
                        currentChannel?.let { store.toggleFavoriteChannel(it.name) }
                    }
                }
            }
        }

        // 右侧半透明毛玻璃换台抽屉
        if (drawerOpen) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(276.dp)
                    .fillMaxHeight()
                    .background(Color(0xCC0F1117))
                    .border(0.7.dp, Color.White.copy(0.10f))
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                // 分组标签栏
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    items(groups) { g ->
                        val on = g == drawerGroup
                        Column(Modifier.clickable { drawerGroup = g }) {
                            Text(
                                g,
                                color = if (on) Color(0xFFEEF0F3) else Color(0x90EEF0F3),
                                fontSize = 12.5.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .width(18.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(if (on) Color(0xFF8AB4FF) else Color.Transparent),
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.6.dp, color = Color.White.copy(0.08f))

                // 频道滚动列表
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(drawerChannels, key = { idx, ch -> "${ch.name}:$idx" }) { idx, ch ->
                        val on = ch.name == currentChannel?.name
                        val globalIdx = channels.indexOfFirst { it.name == ch.name }.coerceAtLeast(0)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) Color(0xFF8AB4FF).copy(0.18f) else Color.Transparent)
                                .clickable {
                                    channelIdx = globalIdx
                                    lineIdx = 0
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%02d".format(idx + 1),
                                color = if (on) Color(0xFF8AB4FF) else Color(0x88EEF0F3),
                                fontSize = 11.sp,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                ch.name,
                                color = if (on) Color(0xFF8AB4FF) else Color(0xD6EEF0F3),
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${ch.urls.size}源", color = Color(0x80EEF0F3), fontSize = 10.sp)
                        }
                    }
                }

                // 底部主备信号源切换
                HorizontalDivider(thickness = 0.6.dp, color = Color.White.copy(0.08f))
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item { Text("信号源", color = Color(0xA6EEF0F3), fontSize = 11.sp) }
                    val urls = currentChannel?.urls.orEmpty()
                    itemsIndexed(urls) { idx, _ ->
                        val on = idx == lineIdx
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) Color(0xFF8AB4FF).copy(0.24f) else Color.White.copy(0.08f))
                                .clickable { lineIdx = idx }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (idx == 0) "主线" else "备用 $idx",
                                color = if (on) Color(0xFF8AB4FF) else Color(0xD0EEF0F3),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LandControlPill(
    label: String,
    active: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0xFF8AB4FF).copy(0.24f) else Color(0x9E101218))
            .border(0.8.dp, if (active) Color(0xFF8AB4FF).copy(0.50f) else Color.White.copy(0.14f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (active) Color(0xFF8AB4FF) else Color(0xFFEEF0F3), modifier = Modifier.size(14.dp))
        Text(label, color = if (active) Color(0xFF8AB4FF) else Color(0xFFEEF0F3), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
