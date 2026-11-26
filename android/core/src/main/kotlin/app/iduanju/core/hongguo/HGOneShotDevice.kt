package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anonymous mobile playback device registrar (`tt-data=a` applog v7.4.x + persisted reuse).
 * A single registered V5 playback identity serves consecutive episodes across sessions and is only
 * rotated if upstream soft-rejects (`HTTP 200 + 0 bytes`) or the user resets devices in Profile.
 */
object HGOneShotDevice {
    data class Identity(
        val did: String,
        val iid: String,
        val cdid: String,
        val openudid: String,
        val registeredAt: Long = System.currentTimeMillis(),
    ) {
        val isUsable: Boolean
            get() = did.isNotEmpty() && iid.isNotEmpty() && cdid.isNotEmpty()
    }

    /** Public anonymous V5 baseline identity used only when fresh `device_register` on a shared NAT IP is rate-limited. */
    val FALLBACK_VERIFIED = Identity(
        did = "1624456208323876",
        iid = "1624456208327972",
        cdid = "91d56f7e-c027-4ce9-9a3c-229d59a13c3b",
        openudid = "56a962410c494bba",
        registeredAt = 0L,
    )
    const val UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
    const val V5_REGISTER_UA = "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    internal val KDF_SALT: ByteArray = Bytes.fromHex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )!!
    private val TT_DATA_PREFIX = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
    private val REGISTER_URLS = listOf(
        "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1",
        "https://log5-applog-hl.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1",
    )
    private const val FILE_NAME = "hongguo_player_v5_device.json"

    @Volatile var enabled: Boolean = true

    fun load(dir: File): Identity? {
        for (name in listOf(FILE_NAME, "$FILE_NAME.bak")) {
            val f = File(dir, name)
            if (!f.exists()) continue
            val id = try {
                val j = JSONObject(f.readText())
                Identity(
                    did = j.optString("did"),
                    iid = j.optString("iid"),
                    cdid = j.optString("cdid"),
                    openudid = j.optString("openudid"),
                    registeredAt = j.optLong("registeredAt", 0L),
                )
            } catch (_: Exception) { null }
            if (id != null && id.isUsable) return id
        }
        return null
    }

    fun save(dir: File, id: Identity) {
        try {
            dir.mkdirs()
            val f = File(dir, FILE_NAME)
            if (f.exists()) runCatching { f.copyTo(File(dir, "$FILE_NAME.bak"), overwrite = true) }
            f.writeText(JSONObject(mapOf(
                "did" to id.did,
                "iid" to id.iid,
                "cdid" to id.cdid,
                "openudid" to id.openudid,
                "registeredAt" to id.registeredAt,
            )).toString())
        } catch (_: Exception) {}
    }

    fun reset(dir: File) {
        File(dir, FILE_NAME).delete()
        File(dir, "$FILE_NAME.bak").delete()
    }

    internal fun ttEncrypt(jsonBytes: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(jsonBytes) }
        }.toByteArray()
        val random = Bytes.random(32)
        val derived = Bytes.sha512(Bytes.sha512(random) + KDF_SALT)
        val plaintext = Bytes.sha512(compressed) + compressed
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
            IvParameterSpec(derived.copyOfRange(16, 32)),
        )
        return TT_DATA_PREFIX + random + cipher.doFinal(plaintext)
    }

    fun registerV5(http: OkHttpClient): Identity? {
        val now = System.currentTimeMillis()
        val cdid = UUID.randomUUID().toString().lowercase()
        val openudid = Bytes.hex(Bytes.random(8))
        val header = mapOf(
            "display_name" to "红果免费短剧", "update_version_code" to 74332, "manifest_version_code" to 74332,
            "app_version_minor" to "7.4.3.32", "aid" to 8662, "channel" to "update_64", "package" to "com.phoenix.read",
            "app_version" to "7.4.3.32", "version_code" to 74332, "sdk_version" to "3.7.0-rc.34-fanqie-xiaoshuo",
            "sdk_target_version" to 29, "git_hash" to "3a88f4e", "os" to "Android", "os_version" to "16", "os_api" to 36,
            "device_model" to "25053RT47C", "device_brand" to "Redmi", "device_manufacturer" to "Redmi",
            "cpu_abi" to "arm64-v8a", "release_build" to "b5e187f", "density_dpi" to 520, "display_density" to "mdpi",
            "resolution" to "1280x2772", "language" to "zh", "timezone" to 8, "access" to "wifi", "not_request_sender" to 0,
            "carrier" to "CHINA MOBILE", "mcc_mnc" to "46000", "rom" to "eng.root.20231116.160340",
            "rom_version" to "W528JS release-keys", "cdid" to cdid,
            "sig_hash" to "56a962410c494bbaf0b58dba20cae56f", "openudid" to openudid,
            "clientudid" to UUID.randomUUID().toString().lowercase(), "ipv6_list" to emptyList<String>(),
            "region" to "CN", "tz_name" to "Asia/Shanghai", "tz_offset" to 28800, "sim_region" to "cn",
            "sim_serial_number" to emptyList<String>(), "oaid_may_support" to false,
            "req_id" to UUID.randomUUID().toString().lowercase(), "device_platform" to "android",
            "custom" to mapOf("account_region_src" to "fallback", "account_region" to "cn", "host_bit" to 64, "dragon_device_type" to "phone"),
            "pre_installed_channel" to "", "apk_first_install_time" to now, "is_system_app" to 0, "sdk_flavor" to "china", "guest_mode" to 0,
        )
        val payload = J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header, "_gen_time" to now))
        val wire = ttEncrypt(payload)
        val stub = Bytes.hex(Bytes.md5(wire)).uppercase()
        val mediaType = "application/octet-stream;tt-data=a".toMediaType()
        for (url in REGISTER_URLS) {
            val req = Request.Builder().url(url)
                .post(wire.toRequestBody(mediaType))
                .header("User-Agent", V5_REGISTER_UA)
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream;tt-data=a")
                .header("log-encode-type", "gzip")
                .header("x-ss-dp", "8662")
                .header("x-ss-req-ticket", now.toString())
                .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
                .header("lc", "101")
                .header("sdk-version", "2")
                .header("passport-sdk-version", "5051452")
                .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
                .header("x-ss-stub", stub)
                .build()
            val raw = try { http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() ?: "" else "" } } catch (_: Exception) { "" }
            if (raw.isEmpty()) continue
            val j = try { J.parse(raw) } catch (_: Exception) { continue }
            val code = J.int(j.opt("code") ?: j.opt("Code"))
            if (code != 0) continue
            val did = J.firstNonEmpty(J.str(j.opt("device_id_str")), J.str(j.opt("device_id")))
            val iid = J.firstNonEmpty(J.str(j.opt("install_id_str")), J.str(j.opt("install_id")))
            if (did.isNotEmpty() && did != "0" && did.all { it.isDigit() } && iid.isNotEmpty() && iid != "0" && iid.all { it.isDigit() }) {
                return Identity(did, iid, cdid, openudid, System.currentTimeMillis())
            }
        }
        return register(http)
    }

    fun register(http: OkHttpClient): Identity? {
        val cdid = UUID.randomUUID().toString(); val openudid = Bytes.hex(Bytes.random(8))
        val q = "aid=8662&app_name=novelread&version_code=73932&version_name=7.3.9.32&device_platform=android&os=android&ssmix=a" +
            "&language=zh&channel=xiaomi_8662_64&device_type=23127PN0CC&device_brand=Xiaomi&resolution=1200*2670&dpi=460" +
            "&host_abi=arm64-v8a&manifest_version_code=73932&update_version_code=73932&ac=wifi&app_type=normal&cpu_support64=true" +
            "&os_api=34&os_version=14&cdid=$cdid&openudid=$openudid"
        val header = mapOf(
            "display_name" to "红果短剧", "aid" to 8662, "channel" to "xiaomi_8662_64", "package" to "com.phoenix.read",
            "app_version" to "7.3.9.32", "version_code" to 73932, "manifest_version_code" to 73932, "update_version_code" to 73932,
            "sdk_version" to "3.9.5", "sdk_target_version" to 34, "os" to "Android", "os_version" to "14", "os_api" to 34,
            "device_model" to "23127PN0CC", "device_brand" to "Xiaomi", "device_manufacturer" to "Xiaomi", "cpu_abi" to "arm64-v8a",
            "release_build" to "UKQ1.230804.001", "density_dpi" to 460, "display_density" to "xxhdpi", "resolution" to "2670x1200",
            "language" to "zh", "timezone" to 8, "region" to "CN", "tz_name" to "Asia/Shanghai", "cdid" to cdid, "openudid" to openudid,
            "clientudid" to UUID.randomUUID().toString(), "google_aid" to "", "req_id" to UUID.randomUUID().toString(),
        )
        val body = J.encodeSorted(mapOf("magic_tag" to "ss_app_log", "header" to header, "_gen_time" to System.currentTimeMillis() / 1000))
        val rb = Request.Builder().url("https://log.snssdk.com/service/2/device_register/?$q")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        for ((k, v) in HGSign.basicHeaders(q, body)) rb.header(k, v)
        rb.header("User-Agent", UA).header("sdk-version", "2")
        val raw = try { http.newCall(rb.build()).execute().use { it.body?.string() ?: "" } } catch (e: Exception) { return null }
        val j = try { J.parse(raw) } catch (e: Exception) { return null }
        val did = J.firstNonEmpty(J.str(j.opt("device_id_str")), J.str(j.opt("device_id")))
        val iid = J.firstNonEmpty(J.str(j.opt("install_id_str")), J.str(j.opt("install_id")))
        if (did.isEmpty() || did == "0" || iid.isEmpty() || iid == "0") return null
        return Identity(did, iid, cdid, openudid, System.currentTimeMillis())
    }
}
