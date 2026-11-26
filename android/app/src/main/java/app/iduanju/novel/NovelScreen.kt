package app.iduanju.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import app.iduanju.ui.components.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iduanju.core.novel.*
import app.iduanju.ui.theme.IdjThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Imported sources and reading progress are private to this installation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelScreen(onBack: () -> Unit, embedded: Boolean = false, onFullScreenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel", 0) }
    val sources = remember { mutableStateListOf<NovelSource>().apply { addAll(runCatching { NovelSources.import(prefs.getString("sources", "[]")!!) }.getOrDefault(emptyList())) } }
    var selected by remember { mutableStateOf(prefs.getString("rankSource", null)?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id ?: "") }
    var continuingFromShelf by remember { mutableStateOf(false) }
    var bookSource by remember { mutableStateOf("") }
    val searchResults = remember { NovelSearchResults() }
    val searchList = rememberLazyListState()
    val rankList = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="import") }
    var hits by remember { mutableStateOf<List<NovelHit>>(emptyList()) }
    var categories by remember { mutableStateOf<List<NovelCategory>>(emptyList()) }
    var category by remember { mutableStateOf<NovelCategory?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf<List<NovelChapter>>(emptyList()) }
    var bookDetails by remember { mutableStateOf<NovelBookDetails?>(null) }
    var shelfRevision by remember { mutableIntStateOf(0) }
    var currentBook by remember { mutableStateOf<NovelHit?>(null) }
    var chapterIndex by remember { mutableIntStateOf(-1) }
    var text by remember { mutableStateOf("") }
    var localText by remember { mutableStateOf("") }
    var localRanges by remember { mutableStateOf<List<NovelParsed.Chapter>>(emptyList()) }
    var localTitle by remember { mutableStateOf("本地小说") }
    var localAuthor by remember { mutableStateOf("") }
    var localBook by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }
    var downloadDone by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var cachedCount by remember { mutableIntStateOf(0) }
    var prefetch by remember { mutableIntStateOf(prefs.getInt("prefetch",3)) }
    var showDownload by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var alternatives by remember { mutableStateOf<List<Pair<NovelSource,NovelHit>>>(emptyList()) }
    var switchBusy by remember { mutableStateOf(false) }
    var switchJob by remember { mutableStateOf<Job?>(null) }
    var readGeneration by remember { mutableIntStateOf(0) }
    var rankGeneration by remember { mutableIntStateOf(0) }
    var rankJob by remember { mutableStateOf<Job?>(null) }
    var prefetchJob by remember { mutableStateOf<Job?>(null) }
    var selectedGroup by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="search") }
    var shelf by remember { mutableStateOf(!(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="rank")) }
    var sourcePage by remember { mutableStateOf(app.iduanju.BuildConfig.DEBUG && app.iduanju.ui.UiEvidence.page=="sources") }
    var importResult by remember { mutableStateOf<String?>(null) }
    var requestFilePicker by remember { mutableStateOf(false) }
    val cache = remember { NovelCache(File(context.filesDir,"novel-cache-v2")) }
    var font by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val readerState = rememberLazyListState()
    val activeSourceId = if (currentBook == null) selected else bookSource
    val source = sources.firstOrNull { it.id == activeSourceId }
    fun submitSearch() {
        keyboard?.hide()
        focus.clearFocus()
        searchList.requestScrollToItem(0)
        searchResults.submit(scope, sources.toList(), query)
    }
    val fullScreen = searching || !shelf || sourcePage || currentBook != null || text.isNotEmpty()
    LaunchedEffect(fullScreen) { if (embedded) onFullScreenChange(fullScreen) }
    DisposableEffect(Unit) { onDispose { if (embedded) onFullScreenChange(false) } }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    // Rank/search jobs are cancellable and generation-scoped. An old source response (including
    // A → B → A switches) must not replace the newest filter selection or clear its busy state.
    fun rankWork(block:suspend (Int)->Unit) {
        rankJob?.cancel();val token=++rankGeneration;busy=true;error=null
        rankJob=scope.launch {
            try{block(token)}catch(e:CancellationException){throw e}catch(e:Exception){if(token==rankGeneration)error=e.message?:"操作失败"}
            finally{if(token==rankGeneration)busy=false}
        }
    }
    DisposableEffect(Unit){onDispose{rankGeneration++;rankJob?.cancel()}}
    fun saveSources() { prefs.edit().putString("sources", JSONArray(sources.map { it.json }).toString()).apply() }
    fun addSources(raw: String) {
        val report = NovelSources.importReport(raw)
        val parsed = report.sources
        require(parsed.isNotEmpty()) { if(report.unsupported>0)report.message else "没有可用的 Legado 文本/媒体/RSS 源" }
        for (s in parsed) { sources.removeAll { it.id == s.id }; sources.add(s) }
        saveSources(); selected = parsed.first().id; importResult = report.message
    }
    suspend fun openLocal(bytes: ByteArray, fileName: String) {
        val parsed = withContext(Dispatchers.IO) { NovelImport.parse(bytes, fileName) }
        withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").writeText(parsed.text) }
        localText = parsed.text; localRanges = parsed.chapters; localTitle = parsed.title; localAuthor = parsed.author.orEmpty()
        prefs.edit().putString("localName", localTitle).putString("localAuthor", localAuthor).apply()
        chapters = parsed.chapters.mapIndexed { i, c -> NovelChapter(c.title, "local:$i", i) }
        chapterIndex = 0; text = parsed.text.substring(parsed.chapters.first().start, parsed.chapters.first().end)
        localBook = true; currentBook = null
        error = null; importResult = "已导入《${parsed.title}》· ${chapters.size} 章"
    }
    suspend fun read(index: Int, selectedSource: NovelSource? = source, bookToSave: NovelHit? = currentBook) {
        if (localBook) {
            val range = localRanges.getOrNull(index) ?: return
            chapterIndex = index
            text = localText.substring(range.start, range.end)
            readerState.requestScrollToItem(0)
            return
        }
        val s = selectedSource ?: return
        val chapter = chapters.getOrNull(index) ?: return
        val book = bookToSave ?: return
        val token = ++readGeneration
        prefetchJob?.cancel()
        if(s.isMedia){
            val toc=chapters.toList()
            val resolve={i:Int->LegadoMedia.resolve(s,toc[i].copy(index=i),book.url,toc.getOrNull(i+1)?.url)}
            val resolved=kotlinx.coroutines.runInterruptible(Dispatchers.IO){resolve(index)}
            // NovelPlayback drives ExoPlayer, which only accepts calls on the main thread.
            withContext(Dispatchers.Main.immediate){if(token==readGeneration){
                NovelPlayback.start(context,NovelPlayback.Queue("${s.id}|${book.url}",book.name,s.json.optInt("bookSourceType"),toc.map{it.title},book.cover,resolve),index,resolved)
                chapterIndex=index;localBook=false
            }}
            return
        }
        val job=currentCoroutineContext()
        val toc=chapters.toList()
        val body=withContext(Dispatchers.IO){
            cache.load(s.id,book.url,chapter.url,checkActive={job.ensureActive();if(token!=readGeneration)throw CancellationException("Reading session changed")}) {
                NovelSources.content(s,chapter,toc.getOrNull(index+1)?.url,book.url)
            }
        }
        if (token != readGeneration) return
        text = body; chapterIndex = index; localBook = false
        bookToSave?.let { book ->
            prefs.edit().putString("last", JSONObject().put("source", s.id).put("name", book.name).put("author", book.author).put("url", book.url).put("chapter", index).toString()).apply()
        }
        // Chapter replacement and scroll reset must share the NEXT layout pass.
        // scrollToItem forces an immediate remeasure of a changing reader tree.
        readerState.requestScrollToItem(0)
        cachedCount = withContext(Dispatchers.IO) { cache.count(s.id,book.url,toc) }
        if (prefetch > 0) prefetchJob = scope.launch(Dispatchers.IO) {
            val speculative=currentCoroutineContext()
            for(i in NovelPrefetchPlan.indices(index,toc.size,prefetch)) {
                speculative.ensureActive()
                try {
                    cache.load(s.id,book.url,toc[i].url,checkActive={speculative.ensureActive()}) {
                        NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url,book.url)
                    }
                } catch(e: CancellationException){throw e} catch(_:Exception){ /* one failed neighbor must not starve the rest */ }
            }
        }
    }
    fun search(reset: Boolean, cat: NovelCategory? = null) {
        if(searching){keyboard?.hide();focus.clearFocus()}
        rankWork { token ->
        val s = source ?: error("请先导入并选择书源")
        val nextPage = if (reset) 1 else page
        val chosen = if (reset) cat else category
        if (reset) { rankList.requestScrollToItem(0); hits = emptyList(); more = false; category = chosen }
        val result = withContext(Dispatchers.IO) { NovelSources.search(s, query, nextPage, chosen) }
        if (selected != s.id || token != rankGeneration) return@rankWork
        val existing = if (reset) emptyList() else hits
        val fresh = result.filter { r -> existing.none { it.url == r.url } }
        hits = existing + fresh; page = nextPage + 1; more = fresh.isNotEmpty(); category = chosen
    }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= 20 * 1024 * 1024) { "文件超过 20 MB" }; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: error("无法读取文件") }
            val name = withContext(Dispatchers.IO) { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "本地小说.txt" } ?: "本地小说.txt" }
            val probe = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (probe.startsWith('[') || probe.startsWith('{')) addSources(NovelImport.decode(bytes)) else openLocal(bytes, name)
        }
    }
    LaunchedEffect(importing, requestFilePicker) {
        if (!importing && requestFilePicker) {
            kotlinx.coroutines.delay(350)
            requestFilePicker = false
            filePicker.launch(arrayOf("*/*"))
        }
    }
    fun filterAction(control:NovelCategory,value:String?) {
        rankWork { token ->
            val sc=source?:return@rankWork
            val previous=category
            hits=emptyList();more=false;page=1
            val refreshed=withContext(Dispatchers.IO){NovelSources.categoryAction(sc,control,value);NovelSources.categories(sc)}
            if(selected!=sc.id || token!=rankGeneration)return@rankWork
            categories=refreshed
            val chosen=NovelSources.refreshedCategory(previous,refreshed,selectedGroup)
            category=chosen
            if(chosen!=null){
                selectedGroup=chosen.group
                val result=withContext(Dispatchers.IO){NovelSources.search(sc,"",1,chosen)}
                if(selected!=sc.id || token!=rankGeneration)return@rankWork
                hits=result.distinctBy{it.url};page=2;more=result.isNotEmpty()
            }
        }
    }
    LaunchedEffect(selected,shelf) {
        rankJob?.cancel();val token=++rankGeneration
        prefs.edit().putString("rankSource", selected).apply()
        hits=emptyList();categories=emptyList();category=null;more=false;page=1;error=null;busy=false
        val sc=sources.firstOrNull { it.id == selected }
        // Match iOS: entering discovery and changing source opens its first real URL category.
        // Previously Android stopped after loading category buttons and left a blank results panel.
        if(sc!=null&&!shelf){
            busy=true
            try{
                val loaded=withContext(Dispatchers.IO){NovelSources.categories(sc)}
                if(token!=rankGeneration||selected!=sc.id)return@LaunchedEffect
                categories=loaded
                val first=loaded.firstOrNull{!it.isControl&&it.url.isNotBlank()}
                selectedGroup=first?.group?:loaded.firstOrNull()?.group.orEmpty();category=first
                if(first!=null){
                    val result=withContext(Dispatchers.IO){NovelSources.search(sc,"",1,first)}
                    if(token!=rankGeneration||selected!=sc.id)return@LaunchedEffect
                    hits=result.distinctBy{it.url};page=2;more=result.isNotEmpty()
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(token==rankGeneration)error=e.message}
            finally{if(token==rankGeneration)busy=false}
        }
    }
    fun download(count: Int?) {
        val book = currentBook ?: return; val s = source ?: return; val toc = chapters.toList();val metadata=bookDetails
        if(downloadBusy)return
        val start=if(count==null)0 else chapterIndex.coerceAtLeast(0)
        val end=if(count==null)toc.size else minOf(toc.size,start+count)
        downloadBusy=true;downloadDone=0;downloadTotal=end-start
        downloadJob=scope.launch {
            try {
                withContext(Dispatchers.IO) { cache.saveBook(s.id,book,toc,metadata) }
                for(i in start until end) {
                    withContext(Dispatchers.IO) {
                        val active=currentCoroutineContext()
                        cache.load(s.id,book.url,toc[i].url,checkActive={active.ensureActive()}) {NovelSources.content(s,toc[i],toc.getOrNull(i+1)?.url,book.url)}
                    }
                    downloadDone++
                    if (currentBook?.url == book.url && bookSource == s.id) cachedCount=withContext(Dispatchers.IO){cache.count(s.id,book.url,toc)}
                }
            } catch(e:CancellationException){error="下载已暂停，可继续";throw e} catch(e:Exception){error="下载中断：${e.message}，已完成章节保留"}
            finally { downloadBusy=false }
        }
    }
    fun findSources() {
        val target=currentBook ?: return
        val selectedSource = bookSource
        val known = searchResults.groups.firstOrNull { g -> g.editions.any { it.source.id == selectedSource && it.book.url == target.url } }?.editions.orEmpty().filter { it.source.id != selectedSource }
        val candidates = sources.filter { it.enabled && it.id != selectedSource && it.json.optString("searchUrl").isNotBlank() }.toList()
        showSwitch=true
        if(switchBusy)return
        switchBusy=true;alternatives=known.map { it.source to it.book }
        switchJob=scope.launch {
            try {
                coroutineScope {
                    val gate = Semaphore(4)
                    candidates.forEach { s -> launch {
                        gate.withPermit {
                            val found = try { withContext(Dispatchers.IO) { NovelSources.search(s,target.name) } }
                                catch(e:CancellationException){throw e} catch(_:Exception){emptyList()}
                            val matches=found.filter{it.name==target.name && (it.author==target.author || it.author.isBlank() || target.author.isBlank())}
                            alternatives=(alternatives+matches.map{s to it}).distinctBy { it.first.id+"|"+it.second.url }
                        }
                    } }
                }
            } finally {switchBusy=false}
        }
    }
    fun openResult(id: String, book: NovelHit) {
        continuingFromShelf = false
        bookSource = id
        currentBook = book
        val saved = cache.books().firstOrNull { it.source == id && it.book.url == book.url }
        bookDetails = saved?.details
        chapters = saved?.chapters.orEmpty()
        cachedCount = cache.count(id, book.url, chapters)
        error = null
    }
    fun back() {
        readGeneration++
        prefetchJob?.cancel()
        when {
            text.isNotEmpty() -> {
                text = ""
                if (continuingFromShelf) { continuingFromShelf = false; currentBook = null }
            }
            currentBook != null -> { currentBook = null; chapters = emptyList(); error = null }
            sourcePage -> sourcePage = false
            searching -> { searching = false; searchResults.clear(); query = "" }
            !shelf -> shelf = true
            else -> onBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled=!embedded || !shelf || sourcePage || searching || currentBook!=null || text.isNotEmpty()) { back() }
    NovelPlayback.state?.let { active ->
        NovelMediaPlayer(active) {
            if (active.queue.key == "${source?.id}|${currentBook?.url}") chapterIndex = active.index
            readGeneration++; NovelPlayback.stop()
            if (continuingFromShelf) { continuingFromShelf = false; currentBook = null }
        }
        return
    }
    val c=IdjThemeColors
    if (!sourcePage && text.isNotEmpty()) {
        val book=currentBook
        val id=if(localBook)"local|$localTitle|${localText.length}|${localText.hashCode()}" else source?.id.orEmpty()+"|"+(book?.url?:"local")
        NovelReaderScreen(id,if(localBook)localTitle else book?.name?:"本地小说",if(localBook)localAuthor else book?.author.orEmpty(),book?.cover.orEmpty(),if (localBook) "本地文件" else source?.name.orEmpty(),chapters,chapterIndex,text,busy,error,cachedCount,
            addedAt=remember(id,shelfRevision){cache.books().firstOrNull{it.source==source?.id&&it.book.url==book?.url}?.addedAt?:0},
            initialOffset=prefs.getInt("reader.offset.$id.${chapterIndex.coerceAtLeast(0)}",0),
            onBack={back()},onChapter={i->work{read(i)}},
            onDownload={i->work{if(i!=chapterIndex)read(i);showDownload=true}},onSwitch={i->work{if(i!=chapterIndex)read(i);findSources()}},
            onPosition={i,offset->prefs.edit().putInt("reader.offset.$id.$i",offset).apply()
                if(localBook)prefs.edit().putString("last",JSONObject().put("source","local").put("name",localTitle).put("url","local").put("chapter",i).toString()).apply()
                else if(book!=null&&source!=null)prefs.edit().putString("last",JSONObject().put("source",source.id).put("name",book.name).put("author",book.author).put("url",book.url).put("chapter",i).toString()).apply()},
            onSearch={q->if(localBook){val query=q.trim();if(query.isEmpty())emptyList() else localRanges.mapIndexedNotNull{index,range->val content=localText.substring(range.start,range.end);val at=content.indexOf(query,ignoreCase=true);if(at<0)null else NovelCache.SearchHit(index,at,content.substring((at-16).coerceAtLeast(0)).take(60).replace('\n',' '))}}else{val sc=source?:error("书源不存在");val b=book?:error("书籍不存在");val toc=chapters.toList();withContext(Dispatchers.IO){cache.search(sc.id,b.url,toc,q)}}},
            onLoadChapter={i->
                if (localBook) {
                    val range=localRanges.getOrNull(i)?:error("章节不存在")
                    localText.substring(range.start,range.end)
                } else {
                    val sc=source?:error("书源不存在");val b=book?:error("书籍不存在");val toc=chapters.toList();val ch=toc.getOrNull(i)?:error("章节不存在");val active=currentCoroutineContext()
                    withContext(Dispatchers.IO){cache.load(sc.id,b.url,ch.url,{active.ensureActive()}){NovelSources.content(sc,ch,toc.getOrNull(i+1)?.url,b.url)}}
                }
            },
            onPrefetch={i->val sc=source;val b=book;val toc=chapters.toList();prefetchJob?.cancel()
                if(!localBook&&sc!=null&&b!=null&&prefetch>0)prefetchJob=scope.launch(Dispatchers.IO){val active=currentCoroutineContext();for(n in NovelPrefetchPlan.indices(i,toc.size,prefetch)){active.ensureActive();try{cache.load(sc.id,b.url,toc[n].url,{active.ensureActive()}){NovelSources.content(sc,toc[n],toc.getOrNull(n+1)?.url,b.url)}}catch(e:CancellationException){throw e}catch(_:Exception){}}}}
        )
    }
    if (!sourcePage && currentBook != null && text.isEmpty()) {
        val book = currentBook!!
        var attempt by remember(book.url, bookSource) { mutableIntStateOf(0) }
        var fetching by remember(book.url, bookSource) { mutableStateOf(bookDetails == null) }
        LaunchedEffect(book.url, bookSource, attempt) {
            val id = bookSource
            fetching = true
            error = null
            try {
                val sc = source ?: error("书源已删除")
                val details = withContext(Dispatchers.IO) { NovelSources.details(sc, book.url) }
                bookDetails = details
                if (details.chapters.isNotEmpty()) {
                    chapters = details.chapters
                    if (cache.books().any { it.source == id && it.book.url == book.url }) {
                        withContext(Dispatchers.IO) { cache.saveBook(id, book, details.chapters, details) }
                    }
                } else if (chapters.isEmpty()) error = "这本书没有可读目录，可切换书源后重试。"
                cachedCount = withContext(Dispatchers.IO) { cache.count(id, book.url, chapters) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "详情加载失败" }
            finally { fetching = false }
        }
        val knownSourceCount = remember(searchResults.groups, alternatives, book.url, bookSource) {
            val fromSearch = searchResults.groups.firstOrNull { g ->
                g.editions.any { it.source.id == bookSource && it.book.url == book.url }
            }?.editions?.size ?: 1
            maxOf(fromSearch, alternatives.size + 1, 1)
        }
        NovelBookDetail(
            book = book,
            info = bookDetails,
            sourceName = source?.name.orEmpty(),
            chapters = chapters,
            loading = fetching || busy,
            error = error,
            onShelf = remember(shelfRevision, book.url, bookSource) { cache.books().any { it.source == bookSource && it.book.url == book.url } },
            downloading = downloadBusy,
            downloadProgress = if (downloadTotal == 0) 0f else downloadDone.toFloat() / downloadTotal,
            cachedCount = cachedCount,
            sourceCount = knownSourceCount,
            onBack = { back() },
            onSwitch = { findSources() },
            onAdd = { scope.launch { withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++ } },
            onDownload = {
                if (source?.isMedia == true) error = "媒体离线下载尚未实现，请使用原生播放"
                else scope.launch { withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++; showDownload = true }
            },
            onRead = { requestedChapter ->
                work {
                    withContext(Dispatchers.IO) { cache.saveBook(bookSource, book, chapters, bookDetails) }; shelfRevision++
                    val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                    val index = if (last.optString("source") == bookSource && last.optString("url") == book.url) last.optInt("chapter", 0).coerceIn(0, chapters.lastIndex) else 0
                    read(requestedChapter ?: index)
                }
            },
            onRetry = { attempt++ },
            mediaType = source?.json?.optInt("bookSourceType") ?: 0,
        )
    }
    if (!sourcePage && currentBook == null && text.isEmpty()) Column(
        Modifier
            .fillMaxSize()
            .then(if (embedded && shelf && !searching) Modifier else Modifier.dsAmbient(cool = !shelf || searching))
            .then(if (!embedded && shelf && !searching) Modifier.statusBarsPadding() else Modifier)
    ) {
        val list = if (searching) searchList else rankList
        val pinned by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
        if (shelf && !searching) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            val hasLocal = File(context.filesDir, "novel-local.txt").exists()
            val shelfEntries = remember(shelfRevision, hasLocal, text) {
                val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                val lastSrc = last.optString("source")
                val lastUrl = last.optString("url")
                val lastCh = last.optInt("chapter", -1)
                val out = mutableListOf<ShelfEntry>()
                if (hasLocal) {
                    val name = prefs.getString("localName", "本地小说") ?: "本地小说"
                    val author = prefs.getString("localAuthor", "") ?: ""
                    val ch = if (lastSrc == "local") lastCh else -1
                    out += ShelfEntry("local", NovelHit(name, author, "local", kind = "TXT 文件"), localRanges.size, ch, localRanges.getOrNull(ch)?.title.orEmpty(), isLocal = true)
                }
                cache.books().sortedByDescending { it.addedAt }.forEach { saved ->
                    val isLast = saved.source == lastSrc && saved.book.url == lastUrl
                    val ch = if (isLast && lastCh in saved.chapters.indices) lastCh else -1
                    val mergedBook = saved.details?.let { d ->
                        saved.book.copy(
                            cover = d.cover.ifBlank { saved.book.cover },
                            author = d.author.ifBlank { saved.book.author },
                            kind = d.kind.ifBlank { saved.book.kind },
                            latest = d.latest.ifBlank { saved.book.latest },
                        )
                    } ?: saved.book
                    out += ShelfEntry(saved.source, mergedBook, saved.chapters.size, ch, saved.chapters.getOrNull(ch)?.title.orEmpty(), isLocal = false)
                }
                out.sortedByDescending { it.currentChapter >= 0 }
            }
            fun openLocalFile() = work {
                val bytes = withContext(Dispatchers.IO) { File(context.filesDir, "novel-local.txt").readBytes() }
                openLocal(bytes, (prefs.getString("localName", "本地小说") ?: "本地小说") + ".txt")
            }
            NovelShelf(
                entries = shelfEntries,
                onContinue = { entry ->
                    if (entry.isLocal) {
                        openLocalFile()
                    } else {
                        openResult(entry.sourceId, entry.book)
                        val sc = sources.firstOrNull { it.id == entry.sourceId }
                        if (sc != null && chapters.isNotEmpty()) work {
                            val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                            val index = if (last.optString("source") == entry.sourceId && last.optString("url") == entry.book.url) last.optInt("chapter") else 0
                            read(index.coerceIn(chapters.indices), sc, entry.book)
                            continuingFromShelf = true
                        }
                    }
                },
                onBook = { entry -> if (entry.isLocal) openLocalFile() else openResult(entry.sourceId, entry.book) },
                onDelete = { targets ->
                    val last = runCatching { JSONObject(prefs.getString("last", "{}")!!) }.getOrDefault(JSONObject())
                    val lastSrc = last.optString("source")
                    val lastUrl = last.optString("url")
                    var clearLast = false
                    for (entry in targets) {
                        if (entry.isLocal) {
                            File(context.filesDir, "novel-local.txt").delete()
                            prefs.edit().remove("localName").remove("localAuthor").apply()
                            if (lastSrc == "local") clearLast = true
                        } else {
                            cache.removeBook(entry.sourceId, entry.book.url)
                            if (lastSrc == entry.sourceId && lastUrl == entry.book.url) clearLast = true
                        }
                    }
                    if (clearLast) prefs.edit().remove("last").apply()
                    shelfRevision++
                },
                onImport = { importResult = null; importing = true },
                onSearch = { searching = true },
                onRank = { shelf = false; searching = false; hits = emptyList() },
                onSources = { sourcePage = true },
                embedded = embedded,
            )
        } else if (searching) {
            Box(Modifier.fillMaxWidth().dsPinned(pinned).statusBarsPadding()) {
                NovelSearchTopBar(
                    value = query,
                    enabledSources = sources.count { it.enabled },
                    onValue = { query = it },
                    onSubmit = ::submitSearch,
                    onBack = { back() },
                    onSources = { sourcePage = true },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
            if (searchResults.total > 0) {
                val inFlight = searchResults.finished < searchResults.total
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 4.dp, bottom = 2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (inFlight) Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                        Text(
                            if (inFlight) "正在搜索 ${searchResults.finished} / ${searchResults.total} 个书源 · ${searchResults.groups.size} 本"
                            else "共 ${searchResults.groups.size} 本 · ${searchResults.total} 个书源",
                            color = c.secondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        if (searchResults.failures > 0) Text("${searchResults.failures} 个失败", color = c.accentWarm, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    }
                    if (inFlight) {
                        Spacer(Modifier.height(7.dp))
                        DSProgressBar(searchResults.finished.toFloat() / searchResults.total)
                    }
                }
            }
            LazyColumn(state = searchList, modifier = Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp)) {
                if (searchResults.groups.isEmpty() && searchResults.finished == searchResults.total) {
                    item {
                        EmptyNovel(
                            if (searchResults.total > 0) "没有找到书籍" else "搜索你的下一本书",
                            "输入书名或作者，同时搜索已启用的书源，相同书籍会自动合并。",
                        )
                    }
                }
                itemsIndexed(searchResults.groups, key = { _, m -> m.first.source.id + "|" + m.first.book.url }) { index, match ->
                    val edition = match.first
                    if (index > 0) Hairline(Modifier.padding(start = if (index == 1) 18.dp else 89.dp, end = 18.dp))
                    NovelSearchRow(
                        book = edition.book,
                        query = query,
                        sourceName = edition.source.name,
                        sourceCount = match.editions.size,
                        lead = index == 0,
                    ) { openResult(edition.source.id, edition.book) }
                }
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            val groups = remember(categories) { categories.map { it.group }.distinct() }
            val groupCats = remember(categories, selectedGroup) { categories.filter { it.group == selectedGroup && !it.isControl } }
            val groupControls = remember(categories, selectedGroup) { categories.filter { it.group == selectedGroup && it.isControl } }

            // Compact single-row rank header: Back + 小说榜单 + Source picker capsule + Search
            Row(
                Modifier.fillMaxWidth().dsPinned(pinned).statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackButton { back() }
                Text("小说榜单", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Box {
                    CapsuleButton(source?.name ?: "选择书源", trailing = Icons.Rounded.ExpandMore) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        sources.filter { it.enabled }.forEach { sc ->
                            DropdownMenuItem(text = { Text(sc.name) }, onClick = { selected = sc.id; expanded = false })
                        }
                    }
                }
                RoundIconButton(Icons.Rounded.Search, "搜书") { searching = true }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

            // Full-width 2-tier horizontal navigation (no cramped 86dp left rail)
            if (groups.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 4.dp)) {
                    UnderlineTabs(groups, selectedGroup, { it }, small = true, onSelect = { group ->
                        if (!busy) {
                            selectedGroup = group
                            val first = categories.firstOrNull { it.group == group && !it.isControl && it.url.isNotBlank() }
                            if (first != null) search(true, first) else { hits = emptyList(); category = null; more = false }
                        }
                    })
                }
                if (groupCats.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groupCats.forEach { cat ->
                            ToneChip(cat.title, cat == category, small = true, enabled = !busy) { search(true, cat) }
                        }
                    }
                }
            } else if (groupCats.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 8.dp)) {
                    UnderlineTabs(groupCats, category ?: groupCats.first(), { it.title }, small = true, onSelect = { cat ->
                        if (!busy) search(true, cat)
                    })
                }
            }

            NovelExploreControls(groupControls, !busy, ::filterAction)

            // 0-card full-width rankings: Top 3 visual podium + #4+ open hairline list
            LazyColumn(state = rankList, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 36.dp)) {
                if (error != null && category != null && hits.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            MiniButton("重试加载榜单") { if (!busy) search(true, category) }
                        }
                    }
                }
                if (hits.isEmpty() && !busy) {
                    item {
                        EmptyNovel(
                            "书源推荐与榜单",
                            if (source == null) "添加书源后，即可搜索和浏览实时榜单。" else "选择上方榜单分类，查看书源实时推荐。",
                        )
                    }
                } else if (hits.isEmpty() && busy) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
                        }
                    }
                } else if (hits.size >= 3) {
                    item(key = "podium") {
                        NovelRankPodium(hits.take(3)) { book -> source?.let { openResult(it.id, book) } }
                        Hairline(Modifier.padding(horizontal = 16.dp))
                    }
                    itemsIndexed(hits.drop(3), key = { _, b -> b.url }) { idx, book ->
                        if (idx > 0) Hairline(Modifier.padding(start = 52.dp, end = 16.dp))
                        NovelRankRow(book, idx + 4) { source?.let { openResult(it.id, book) } }
                    }
                } else {
                    itemsIndexed(hits, key = { _, b -> b.url }) { idx, book ->
                        if (idx > 0) Hairline(Modifier.padding(start = 52.dp, end = 16.dp))
                        NovelRankRow(book, idx + 1) { source?.let { openResult(it.id, book) } }
                    }
                }
                if (more) {
                    item(key = "more") {
                        if (error != null) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                MiniButton("重试加载榜单") { if (!busy) search(false) }
                            }
                        } else {
                            // TV fix: only auto-load when the list actually overflows the
                            // viewport AND the user has scrolled near the end. On a big TV
                            // screen a short ranking fits whole, so the footer is always
                            // composed — the unconditional effect used to chain-load every
                            // page back to back, seen as constant flicker.
                            val nearEnd by remember { derivedStateOf { val info = rankList.layoutInfo; info.totalItemsCount > info.visibleItemsInfo.size && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3 } }
                            LaunchedEffect(nearEnd, page, category?.id, busy) { if (nearEnd && !busy) search(false) }
                        }
                    }
                }
            }
        }
    }
    if(sourcePage)NovelSourcesPage(sources.toList(),{sourcePage=false},{importResult=null;importing=true},onToggle={sc,on->
        val i=sources.indexOfFirst{it.id==sc.id};if(i>=0)sources[i]=NovelSource(JSONObject(sc.json.toString()).put("enabled",on));saveSources()
    },onDelete={sc->sources.removeAll{it.id==sc.id};saveSources();if(selected==sc.id)selected=sources.firstOrNull()?.id.orEmpty()})

    if(showDownload) NovelPage("缓存与下载",{showDownload=false}) {
        NovelPanel {
            Text(currentBook?.name.orEmpty(),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Text("已缓存 $cachedCount / ${chapters.size} 章",fontSize=13.sp,color=IdjThemeColors.secondary)
            if(downloadBusy){
                LinearProgressIndicator(progress={if(downloadTotal==0)0f else downloadDone.toFloat()/downloadTotal},modifier=Modifier.fillMaxWidth())
                Text("本次 $downloadDone / $downloadTotal",fontSize=13.sp)
                OutlinedButton(onClick={downloadJob?.cancel()}){Text("暂停下载")}
            }
            error?.let {Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        }
        NovelPanel {
            Text("离线下载",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={download(50)},enabled=!downloadBusy,modifier=Modifier.weight(1f)){Text("缓存后 50 章")}
                OutlinedButton(onClick={download(200)},enabled=!downloadBusy,modifier=Modifier.weight(1f)){Text("缓存后 200 章")}
            }
            Button(onClick={download(null)},enabled=!downloadBusy,modifier=Modifier.fillMaxWidth()){Text("下载全本 / 继续未完成下载")}
        }
        NovelPanel {
            Text("提前缓存",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(0,3,10,20).forEach{n->
                FilterChip(selected=prefetch==n,onClick={prefetch=n;prefs.edit().putInt("prefetch",n).apply()},label={Text(if(n==0)"关闭" else "$n 章")})
            }}
            Text("阅读时自动预取后续章节，减少翻页等待。",fontSize=12.sp,color=IdjThemeColors.secondary)
        }
        Text("保持应用前台下载。已缓存章节可断网阅读，暂停或中断后可继续，已完成章节不会重复请求。",fontSize=12.sp,color=IdjThemeColors.secondary)
    }
    if(showSwitch)AlertDialog(onDismissRequest={switchJob?.cancel();showSwitch=false},title={Text("切换书籍来源")},text={LazyColumn{
        if(switchBusy)item{Text("正在搜索同名书籍…")}
        items(alternatives,key={it.first.id+it.second.url}){(s,book)->TextButton(onClick={work{
            val oldTitle=chapters.getOrNull(chapterIndex)?.title
            val details=withContext(Dispatchers.IO){NovelSources.details(s,book.url)}
            val toc=details.chapters
            require(toc.isNotEmpty()){"该源目录为空"}
            val match=toc.indexOfFirst{it.title.replace(Regex("\\s+"),"")==oldTitle?.replace(Regex("\\s+"),"")}.takeIf{it>=0} ?: chapterIndex.coerceIn(0,toc.lastIndex)
            withContext(Dispatchers.IO){cache.saveBook(s.id,book,toc,details)}
            switchJob?.cancel();bookSource=s.id;currentBook=book;bookDetails=details;chapters=toc;showSwitch=false;if(text.isNotEmpty())read(match,s,book)
        }}){Text(s.name)}}
        if(!switchBusy&&alternatives.isEmpty())item{Text("未找到同名同作者书籍，可启用更多书源后重试")}
    }},confirmButton={TextButton(onClick={switchJob?.cancel();showSwitch=false}){Text("关闭")}})
    if(importing)ModalBottomSheet(onDismissRequest={importing=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=c.surface,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),dragHandle=null) {
        val importKeyboard=LocalSoftwareKeyboardController.current
        val importFocus=LocalFocusManager.current
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("novel.import.sheet").padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("导入书籍与书源",color=c.text,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));RoundIconButton(Icons.Rounded.Close,"关闭"){importing=false}}
            Text("书籍",color=c.secondary,fontSize=12.sp)
            Row(Modifier.fillMaxWidth().dsCard(16.dp).pressClick {requestFilePicker=true;importing=false}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.MenuBook);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("导入本地书籍",color=c.text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("TXT · UTF-8 / GB18030 / UTF-16",color=c.secondary,fontSize=12.sp)};Icon(Icons.Rounded.ChevronRight,null,tint=c.tertiary)}
            Text("书源",color=c.secondary,fontSize=12.sp)
            OutlinedTextField(importText,{importText=it},Modifier.fillMaxWidth(),placeholder={Text("粘贴书源网址或 JSON")},shape=RoundedCornerShape(14.dp),maxLines=3,
                trailingIcon={TextButton(onClick={val clip=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;importText=clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}){Text("粘贴")}})
            PillButton(if(busy)"正在导入…" else "导入书源",Modifier.fillMaxWidth(),Icons.Rounded.Add,enabled=!busy&&importText.isNotBlank()) {importFocus.clearFocus();importKeyboard?.hide();work{
                val raw=importText.trim()
                val data=if(raw.startsWith("https://")||raw.startsWith("http://"))withContext(Dispatchers.IO){NovelSources.fetch(raw,NovelSource(JSONObject().put("bookSourceUrl",raw)))}else raw
                addSources(data)
            }}
            Row(Modifier.fillMaxWidth().pressClick {val clip=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;importText=clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.ContentPaste);Spacer(Modifier.width(12.dp));Text("从剪贴板导入",color=c.text,fontSize=15.sp)}
            Hairline()
            Row(Modifier.fillMaxWidth().pressClick {requestFilePicker=true;importing=false}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){IconWell(Icons.Rounded.FolderOpen);Spacer(Modifier.width(12.dp));Text("从文件导入书源",color=c.text,fontSize=15.sp)}
            importResult?.let{DSStatus(it,StatusTone.Ok)}
            error?.let{Text(it,color=c.accentWarm,fontSize=13.sp)}
            Text("支持 Legado 文本、有声、视频书源与 RSS 订阅。书源与阅读记录仅保存在当前设备。",color=c.tertiary,fontSize=12.sp,lineHeight=19.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}
