package app.iduanju.core.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Plugin / 采集站 network guard (iOS NetGuard): no IP literals, nothing resolving to loopback/private ranges. */
object NetGuard {
    @Volatile var resolverOverride: ((String) -> List<String>)? = null
    private val cache = HashMap<String, Pair<Long, Boolean>>()

    fun isIPLiteral(host: String): Boolean {
        val h = host.trim('[', ']')
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h)) return true
        return h.contains(':') && Regex("""^[0-9a-fA-F:.]+$""").matches(h)
    }

    fun isBlockedIP(ip: String): Boolean {
        val a = try { InetAddress.getByName(ip) } catch (_: Exception) { return true }
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        if (a is Inet4Address) {
            val b = a.address.map { it.toInt() and 0xff }
            if (b[0] == 100 && b[1] in 64..127) return true // CGNAT
            if (b[0] == 0) return true
        }
        if (a is Inet6Address) { val b0 = a.address[0].toInt() and 0xfe; if (b0 == 0xfc) return true } // ULA
        return false
    }

    private fun isIPv6ULA(ip: String): Boolean {
        val a = try { InetAddress.getByName(ip) } catch (_: Exception) { return false }
        return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
    }

    fun resolvesToBlocked(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        synchronized(cache) { cache[h]?.let { (t, v) -> if (!v && System.currentTimeMillis() - t < 300_000) return false } }
        val addrs = resolverOverride?.invoke(h) ?: try { InetAddress.getAllByName(h).map { it.hostAddress ?: "" } } catch (_: Exception) { emptyList() }
        val hasAllowed = addrs.any { !isBlockedIP(it) }
        // Proxy Fake-IP (Clash / sing-box) often returns a 198.18.x.x IPv4 paired with an fc00::/18 IPv6;
        // do not block the whole host for the companion Fake-IPv6 when an allowed address is present.
        val blocked = addrs.any { isBlockedIP(it) && !(hasAllowed && isIPv6ULA(it)) }
        if (!blocked && addrs.isNotEmpty()) {
            synchronized(cache) { cache[h] = System.currentTimeMillis() to false }
        }
        return blocked
    }
}
