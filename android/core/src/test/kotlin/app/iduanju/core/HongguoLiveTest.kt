package app.iduanju.core

import app.iduanju.core.hongguo.HGNet
import app.iduanju.core.hongguo.HGPlayerDevice
import app.iduanju.core.hongguo.HGRankGenre
import app.iduanju.core.hongguo.HGRankSub
import app.iduanju.core.hongguo.HongguoClient
import app.iduanju.core.util.J
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object LiveTestNet {
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    fun configureIfRequested(): OkHttpClient? {
        val bindIp = System.getProperty("idj.bindLocal")?.trim()?.takeIf { it.isNotEmpty() } ?: return HGNet.clientOverride
        HGNet.clientOverride?.let { return it }
        val localAddr = InetAddress.getByName(bindIp)
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket = Socket().apply { bind(InetSocketAddress(localAddr, 0)) }
            override fun createSocket(host: String, port: Int): Socket = Socket(host, port, localAddr, 0)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = Socket(host, port, localAddr, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port, localAddr, 0)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = Socket(address, port, localAddr, localPort)
        }
        val dohHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(factory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "dns.alidns.com") {
                        listOf(
                            InetAddress.getByAddress("dns.alidns.com", byteArrayOf(223.toByte(), 5, 5, 5)),
                            InetAddress.getByAddress("dns.alidns.com", byteArrayOf(223.toByte(), 6, 6, 6)),
                        )
                    } else {
                        listOf(InetAddress.getByName(hostname))
                    }
            })
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
        val dohDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return listOf(InetAddress.getByName(hostname))
                dnsCache[hostname]?.let { return it }
                val req = Request.Builder()
                    .url("https://dns.alidns.com/resolve?name=$hostname&type=1")
                    .header("Accept", "application/json")
                    .build()
                dohHttp.newCall(req).execute().use { resp ->
                    val j = J.parse(resp.body?.string() ?: "{}")
                    val ips = J.arr(j.opt("Answer")).mapNotNull { a ->
                        val d = J.dict(a)
                        if (J.int(d.opt("type")) == 1) InetAddress.getByAddress(hostname, InetAddress.getByName(J.str(d.opt("data"))).address) else null
                    }
                    if (ips.isNotEmpty()) {
                        dnsCache[hostname] = ips
                        return ips
                    }
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .socketFactory(factory)
            .dns(dohDns)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        runCatching {
            val ipInfo = client.newCall(Request.Builder().url("https://myip.ipip.net/").build()).execute().use { it.body?.string()?.trim() }
            println("[LiveTestNet] egress=$ipInfo reading.snssdk.com=${dohDns.lookup("reading.snssdk.com")}")
        }.onFailure { println("[LiveTestNet] egress check failed: $it") }
        HGNet.clientOverride = client
        return client
    }
}

/**
 * Live network tests against 红果. Run with: ./gradlew :core:test -Plive=1 [-PhgDevice=did,iid] [-PbindLocal=192.168.x.x]
 * Soft-reject evidence is printed to stderr (HTTP code + empty body).
 */
class HongguoLiveTest {
    private lateinit var client: HongguoClient

    @Before
    fun setUp() {
        assumeTrue("set -Plive=1 to run", System.getProperty("idj.live") == "1")
        LiveTestNet.configureIfRequested()
        val dir = File("build/hg-live-data").also { it.mkdirs() }
        System.getProperty("idj.hgDevice")?.takeIf { it.contains(",") }?.let { s ->
            val p = s.split(",", limit = 2)
            HGPlayerDevice.overrideProvider = { p[0].trim() to p[1].trim() }
            println("[live] using override player device did=${p[0].trim().take(4)}…")
        }
        client = HongguoClient(dir)
    }

    @Test
    fun deviceRegisterAndRank() {
        val d = client.ensureDevice()
        println("[live] device_register did=${d.deviceId} iid=${d.iid}")
        assertTrue(d.deviceId.isNotEmpty())
        val page = client.rank(HGRankSub.hot, HGRankGenre.all)
        println("[live] rank items=${page.items.size} first=${page.items.firstOrNull()?.title}")
        assertTrue("rank empty", page.items.isNotEmpty())
    }

    @Test
    fun detailAndResolve() {
        val page = client.rank(HGRankSub.hot, HGRankGenre.all)
        assumeTrue(page.items.isNotEmpty())
        val sid = page.items.first().seriesId
        println("[live] detail sid=$sid title=${page.items.first().title}")
        val detail = client.detail(sid, web = false)
        println("[live] detail eps=${detail.episodes.size} title=${detail.title} tags=${detail.tags} related=${detail.relatedSeries.size} cast=${detail.cast.size} fav=${detail.favoriteCount}")
        assertTrue("no episodes", detail.episodes.isNotEmpty())
        println("[live] detail route=${client.lastDetailRoute} (identity=${client.detailIdentity})")
        for (mode in listOf("mint", "registered", "oneshot")) {
            client.detailIdentity = mode
            val dw = client.detail(sid)
            println("[live] detail(web) identity=$mode route=${client.lastDetailRoute} tags=${dw.tags} related=${dw.relatedSeries.size} ${dw.relatedSeries.map { it.title }.take(4)} cast=${dw.cast.map { it.name }.take(3)} fav=${dw.favoriteCount}")
        }
        client.detailIdentity = "registered"
        val ep = detail.episodes.first()
        println("[live] resolve vid=${ep.vid}")
        try {
            val r = client.resolve(ep.vid)
            println("[live] resolve route=${client.lastPureRoute} ok streams=${r.streams.size} url=${r.url?.take(60)} key=${r.key != null} err=${r.err}")
            assertTrue(r.streams.isNotEmpty() || r.url != null)
            // Probe first bytes of media
            r.url?.let { u ->
            }
            r.streams.firstOrNull()?.let { s ->
                val http = LiveTestNet.configureIfRequested() ?: OkHttpClient()
                val req = Request.Builder().url(s.url)
                    .header("User-Agent", "com.phoenix.read/73932")
                    .header("Range", "bytes=0-65535")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val n = resp.body?.bytes()?.size ?: 0
                    println("[live] media probe HTTP $code bytes=$n encrypted=${s.key.isNotEmpty()}")
                    assertTrue("media HTTP $code", code in 200..299)
                    assertTrue("media empty", n > 0)
                }
            }
        } catch (e: Exception) {
            System.err.println("[live] RESOLVE FAILED: ${e.message}")
            throw e
        }
    }

    @Test
    fun signedSearch() {
        val page = client.search("总裁")
        println("[live] search 总裁 items=${page.items.size} first=${page.items.firstOrNull()?.title} hasMore=${page.hasMore}")
        assertTrue("search empty", page.items.isNotEmpty())
    }

    @Test
    fun signedCategory() {
        val panel = client.panel()
        println("[live] panel rows=${panel.rows.size} ${panel.rows.map { it.name + ":" + it.items.size }}")
        assertTrue("panel empty", panel.rows.isNotEmpty())
        val all = client.category(emptyList())
        println("[live] category all items=${all.items.size} first=${all.items.firstOrNull()?.title}")
        assertTrue("category empty", all.items.isNotEmpty())
        val genre = panel.rows.firstOrNull { it.type == "genre" }?.items?.firstOrNull()
        if (genre != null) {
            val filtered = client.category(listOf(genre.id))
            println("[live] category ${genre.name} items=${filtered.items.size} first=${filtered.items.firstOrNull()?.title}")
            assertTrue("filtered category empty", filtered.items.isNotEmpty())
        }
    }
}
