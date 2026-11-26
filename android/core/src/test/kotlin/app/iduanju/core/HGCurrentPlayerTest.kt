package app.iduanju.core

import app.iduanju.core.hongguo.HGCurrentPlayer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class HGCurrentPlayerTest {
    private fun fixture(name: String)=javaClass.getResourceAsStream("/hongguo/$name")!!.bufferedReader().use{it.readText()}
    private fun sha(b: ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun bytes(o: JSONObject,k:String)=Base64.getDecoder().decode(o.getString(k))
    @Test fun nativeEnvelopeAndTransforms() {
        val f=JSONObject(fixture("current_crypto.json"));assertTrue(f.getBoolean("synthetic_only"))
        val whole=f.getJSONArray("whole");assertEquals(32,whole.length())
        for(i in 0 until whole.length()) {
            val v=whole.getJSONObject(i);val x=v.getJSONObject("inputs")
            val got=HGCurrentPlayer.compute(x.getString("raw_query"),bytes(x,"record_digest"),bytes(x,"factor_pb"),bytes(x,"material"),x.getString("license_id"),x.getString("aid"),
                x.getLong("seconds").toULong(),x.getInt("gorgon_nonce"),x.getInt("native_gorgon_flags"),x.getLong("helios_random").toUInt(),x.getLong("medusa_random1").toUInt(),
                x.getLong("medusa_random2").toUInt(),x.getLong("local_buffer_address").toULong(),x.getLong("previous_thread_state").toUInt())
            assertEquals(v.getLong("expected_thread_state").toUInt(),got.second)
            val expected=v.getJSONObject("expected_header_sha256")
            for(k in expected.keys()) assertEquals("vector $i $k",expected.getString(k),sha(got.first.getValue(k).toByteArray()))
        }
        val transforms=f.getJSONArray("transforms");assertEquals(22,transforms.length())
        for(i in 0 until transforms.length()) {val v=transforms.getJSONObject(i)
            assertEquals("transform $i",v.getString("expected_sha256"),sha(HGCurrentPlayer.transform(bytes(v,"plain"),bytes(v,"key"),bytes(v,"iv"),v.getInt("branch"))))}
    }
    @Test fun nativeFactors() {
        val a=JSONArray(fixture("current_factor.json"));assertEquals(48,a.length())
        for(i in 0 until a.length()){val v=a.getJSONObject(i);val input=ByteArray(52){(it*37+v.getInt("public_input_seed")).toByte()}
            assertEquals("factor $i",v.getString("output_sha256"),sha(HGCurrentPlayer.factor(input,v.getLong("public_seconds").toUInt(),v.getInt("toy_seed"))))}
    }
    @Test fun requestBindsFinalTicketBodyAndIdentity() {
        val pairs=listOf("aid" to "8662","device_id" to "1000000000000000001","version_name" to "7.3.9.32","ts" to "stale","_rticket" to "stale")
        val body="""{"video_id":"synthetic-1"}""".toByteArray();val entropy=ByteArray(24){if(it<20)(it+1).toByte() else 0}
        var crossed=false
        for(i in 0..31){val now=1794827000999+i*1000L;val (q,h)=HGCurrentPlayer.sign(pairs,now,pairs[1].second,pairs[2].second,body,entropy)
            val fields=q.split('&').map{it.split('=',limit=2)};assertEquals(1,fields.count{it[0]=="ts"});assertEquals(1,fields.count{it[0]=="_rticket"})
            val values=fields.associate{it[0] to it[1]};val ticket=values.getValue("_rticket").toLong()
            assertTrue(ticket in now until now+128);assertEquals((ticket/1000).toString(),h["x-khronos"]);assertEquals(ticket.toString(),h["x-ss-req-ticket"])
            crossed=crossed||ticket/1000!=now/1000
        };assertTrue(crossed)
        val baseline=HGCurrentPlayer.sign(pairs,1794827000123,pairs[1].second,pairs[2].second,body,entropy).second
        assertNotEquals(baseline["x-medusa"],HGCurrentPlayer.sign(pairs,1794827000123,pairs[1].second,pairs[2].second,"changed".toByteArray(),entropy).second["x-medusa"])
    }
    @Test fun playbackV5GoldenVector() {
        val pairs = listOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74532", "version_name" to "7.4.5.32",
            "channel" to "update_64", "device_type" to "25053RT47C", "os_version" to "16",
            "iid" to "1000000000000002", "cdid" to "00000000-0000-4000-8000-000000000001", "device_id" to "1000000000000001",
        )
        val entropy = ByteArray(16) { (it + 1).toByte() }
        val (q, h) = HGCurrentPlayer.signPlaybackV5(
            pairs = pairs,
            nowMillis = 1795500000123L,
            body = """{"test":1}""".toByteArray(),
            includeBodyHeaders = true,
            entropy = entropy,
            processNonce = "fixedProcessNonce_000123",
            processStarted = 1700000000000L,
            field21 = 312,
        )
        assertEquals(
            "aid=8662&app_name=novelread&version_code=74532&version_name=7.4.5.32&channel=update_64&device_type=25053RT47C&os_version=16&iid=1000000000000002&cdid=00000000-0000-4000-8000-000000000001&device_id=1000000000000001&_rticket=1795500000150",
            q,
        )
        assertEquals("1795500000", h["x-khronos"])
        assertEquals("BQYHCBASuL65vfZGYSox1hnwyZB2YUGk7FjoLSo2Vo/XGzDK", h["x-helios"])
        assertEquals(
            "5ScFa81sSqGpUggo35KrR8JKyT0JCgABMootBg8UAf0DWL+Z40FS4c6If5a4H+wLyhAjkaSOe8V2FEY2SGCKlwmL+YJosK5cW72mO/hD6eHlyWO4o4EmOONZ/SKUpJ7TrWfi9KTjgndRtWFz0rpClBmfZC+f7hu1RqMhCvtT4AMPaKoe9fW3/VEoyWOHTRCK/s5uTbm0Qyv0gZARqU+9S2uBEHLTFHGFP/PsZr8PDWzWv2c6h3xGtfWZTbSa3de8S9aSPs0Z0rUNPcEMNHSO0t+G2fPEV0V0/nc7o0vZtXFDixR6j9io+h8402vfwu7Y8gNKqyOtN4T1QE8FxjlbD3X38iNMusJ3Pi3ZGIQBz6hVjmhIwm+exmTmj6t8ORR/gA6CndcJIqcpDq3kYBH7WqA8xzBNN1bFduUFn43Dtklvz4jhWPJo0zpGB9cHBX3Z8bKqWidiod226NGxOac+a4vh9Lyg3mYT6yw5ZKZ7Rdfo4OfFZv2kPgTPZ7s0iqoEmMMEsBNseX/DEtmssN3Ebd2IJosc3kBCF+JevSs6YPzUCwAKFZp7N5G7arc/0Es7r+GImcAlxk2JfcOKoSFhbikrGYaX1qYtWk69UZRiExQIqImvfmUVMytS/k/9VHX9FpmY/6f4//+n+P8LDA==",
            h["x-medusa"],
        )
        assertEquals("44E7A186DB2C24F55E9BF22E427DF2AD", h["x-ss-stub"])
        assertNull(h["x-argus"])
        assertNull(h["x-gorgon"])
        assertNull(h["x-ladon"])
    }
}
