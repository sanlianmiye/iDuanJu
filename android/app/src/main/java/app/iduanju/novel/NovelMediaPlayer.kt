package app.iduanju.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.ui.player.PlayerScreen
import app.iduanju.ui.player.VideoQueueSession
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.Player
import app.iduanju.core.novel.LegadoMedia
import app.iduanju.core.util.NetGuard
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.net.InetAddress
import java.io.IOException
import javax.net.SocketFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Defense before TCP connect (network interceptors run after connect, and IP literals may skip Dns). */
internal class GuardedMediaSocketFactory:SocketFactory(){
 override fun createSocket():Socket=object:Socket(){
  override fun connect(endpoint:SocketAddress?,timeout:Int){
   val target=endpoint as? InetSocketAddress
   if(target?.address==null || target.port !in listOf(80,443) || NetGuard.isBlockedIP(target.address.hostAddress.orEmpty()))throw IOException("媒体连接拒绝内网地址或非标准端口")
   super.connect(endpoint,timeout)
  }
 }
 override fun createSocket(host:String,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
}

/** Validate each request, not just DNS lookup: OkHttp may bypass Dns for IP-literal redirects. */
internal fun guardedMediaRequest(req:okhttp3.Request,origin:String):okhttp3.Request {
 LegadoMedia.checkedURL(req.url.toString())
 return if(LegadoMedia.sameOrigin(req.url.toString(),origin))req else req.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build()
}

@Composable
fun NovelMediaPlayer(state: NovelPlayback.State, onClose: () -> Unit) {
    val player = NovelPlayback.exo ?: return
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle, player, state.video) {
        val observer = LifecycleEventObserver { _, event -> if (state.video && event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(player) { onDispose { if (NovelPlayback.state?.video == true) NovelPlayback.stop() } }
    BackHandler(onBack = onClose)
    var position by remember(state.index) { mutableLongStateOf(0) }
    var duration by remember(state.index) { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player, state.index) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            delay(250)
        }
    }
    Box(Modifier.fillMaxSize().testTag("novel.media.player")) {
        Box(
            Modifier.fillMaxSize().testTag("novel.media.status").semantics {
                stateDescription = "已播放 %.2f 秒 · %s".format(java.util.Locale.ROOT, position / 1000.0, if (playing) "播放中" else "暂停")
            },
        ) {
            if (state.video) {
                val detail = remember(state.queue) {
                    val episodes = state.queue.titles.mapIndexed { i, title -> HGEpisode(i + 1, i.toString(), title = title) }
                    HGSeriesDetail(seriesId = "novel:" + state.queue.key, title = state.queue.book, episodes = episodes,
                        totalEps = episodes.size, source = "视频书", cover = state.queue.cover)
                }
                val loading = state.media == null && state.failure == null
                val session = remember(player, detail, state.index, loading, state.failure) {
                    VideoQueueSession(player, detail, state.index, loading, state.failure, NovelPlayback::select)
                }
                PlayerScreen(detail.seriesId, state.index + 1, onClose, session)
            } else {
                AudioBookPlayer(state, position, duration, playing, onClose)
            }
        }
    }
}

private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioBookPlayer(state: NovelPlayback.State, position: Long, duration: Long, playing: Boolean, onClose: () -> Unit) {
    val player = NovelPlayback.player ?: return
    var listOpen by remember { mutableStateOf(false) }
    var drag by remember(state.index) { mutableStateOf<Float?>(null) }
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "关闭播放") }
                Text("有声书", style = MaterialTheme.typography.titleMedium)
                IconButton({ listOpen = true }) { Icon(Icons.Default.QueueMusic, "章节列表") }
            }
            val cover: @Composable (Modifier) -> Unit = { modifier ->
                BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(minOf(maxHeight * .9f, maxWidth * .9f, 320.dp)).clip(RoundedCornerShape(18.dp))
                        .background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val radius = size.minDimension / 2
                            drawCircle(colors.onSecondaryContainer.copy(alpha = .9f), radius * .87f)
                            for (ring in 4..12) drawCircle(colors.secondaryContainer.copy(alpha = .16f), radius * ring / 15,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                            drawCircle(colors.primaryContainer, radius * .31f)
                        }
                        Icon(Icons.Default.Headphones, null, Modifier.fillMaxSize(.15f), tint = colors.onPrimaryContainer)
                        if (state.queue.cover.isNotBlank()) coil.compose.AsyncImage(state.queue.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            val transport: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(state.queue.book, color = colors.onSurfaceVariant, maxLines = 1)
                    state.failure?.let { TextButton({ NovelPlayback.select(state.index) }, Modifier.testTag("novel.media.error")) { Text("$it · 重试") } }
                    Spacer(Modifier.height(16.dp))
                    Slider(value = drag ?: (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f), enabled = duration > 0 && state.media != null,
                        onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { player.seekTo((it * duration).toLong()) }; drag = null })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(drag?.let { (it * duration).toLong() } ?: position)); Text(clock(duration)) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(NovelPlayback::previous, enabled = state.hasPrevious) { Icon(Icons.Default.SkipPrevious, "上一章") }
                    FilledIconButton({ if (playing) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }, Modifier.size(64.dp), enabled = state.media != null && state.failure == null) {
                        if (state.media == null && state.failure == null) CircularProgressIndicator(Modifier.size(24.dp))
                        else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(34.dp))
                    }
                    IconButton(NovelPlayback::next, enabled = state.hasNext) { Icon(Icons.Default.SkipNext, "下一章") }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        cover(Modifier.weight(.85f).fillMaxHeight())
                        Column(Modifier.weight(1f)) { transport() }
                    }
                } else Column(Modifier.fillMaxSize()) {
                    cover(Modifier.weight(1f).fillMaxWidth())
                    transport()
                }
            }
        }
    }
    if (listOpen) ModalBottomSheet(onDismissRequest = { listOpen = false }) {
        androidx.compose.foundation.lazy.LazyColumn { items(state.queue.titles.size) { i ->
            TextButton(onClick = { if (i != state.index) NovelPlayback.select(i); listOpen = false }, modifier = Modifier.fillMaxWidth()) {
                Text(state.queue.titles[i], color = if (state.index == i) colors.primary else colors.onSurface)
            }
        } }
    }
}
