package app.iduanju.core

import app.iduanju.core.hongguo.HGCurrentPlayer
import app.iduanju.core.hongguo.HGProtocolConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HGProtocolConfigTest {
    private val repoFile = File("../../configs/hongguo-protocol.json")
    private fun repoText() = repoFile.readText()

    private fun mutate(edit: (JSONObject) -> Unit): String = JSONObject(repoText()).also(edit).toString()

    @Test fun repoJsonEqualsBuiltinDefault() {
        val parsed = HGProtocolConfig.parse(repoText())
        assertNotNull("configs/hongguo-protocol.json must parse", parsed)
        assertEquals(HGProtocolConfig.builtin, parsed)
        assertEquals(312, parsed!!.field21)
    }

    @Test fun rejectsMalformedOrUnsafeConfigs() {
        assertNull(HGProtocolConfig.parse("not json"))
        assertNull(HGProtocolConfig.parse(mutate { it.put("schema", 2) }))
        assertNull(HGProtocolConfig.parse(mutate { it.put("configVersion", 0) }))
        assertNull(HGProtocolConfig.parse(mutate { it.remove("medusa") }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("medusa").put("field21", 70000) }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("preload").put("hosts", org.json.JSONArray(listOf("evil.example.com"))) }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("preload").put("hosts", org.json.JSONArray(listOf("fqnovel.com.evil.com"))) }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("tab").put("path", "../etc/passwd") }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONArray("appQuery").put("device_id=1") }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("preload").put("drScene", "x") }))
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("fallbackIdentity").put("did", "abc") }))
        assertNull(HGProtocolConfig.parse("x".repeat(70_000)))
    }

    @Test fun acceptsHotUpdateOfField21AndOmission() {
        assertEquals(400, HGProtocolConfig.parse(mutate { it.getJSONObject("medusa").put("field21", 400) })!!.field21)
        assertNull(HGProtocolConfig.parse(mutate { it.getJSONObject("medusa").put("field21", JSONObject.NULL) })!!.field21)
        assertEquals("/novel/player/multi_video_model/preload/v1",
            HGProtocolConfig.parse(mutate { it.getJSONObject("preload").put("path", "novel/player/multi_video_model/preload/v1") })!!.preloadPath)
    }

    @Test fun field21ChangesTheSignedMessage() {
        val pairs = listOf("aid" to "8662", "version_name" to "7.4.5.32", "device_id" to "1000000000000001")
        val entropy = ByteArray(16) { (it + 1).toByte() }
        fun sign(f: Int?) = HGCurrentPlayer.signPlaybackV5(pairs, 1795500000123L, """{"t":1}""".toByteArray(), true, entropy,
            "fixedProcessNonce_000123", 1700000000000L, f).second["x-medusa"]
        assertNotEquals(sign(312), sign(400))
        assertNotEquals(sign(312), sign(null))
        assertEquals(sign(312), sign(312))
    }
}
