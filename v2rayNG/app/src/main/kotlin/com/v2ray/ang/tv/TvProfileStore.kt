package com.v2ray.ang.tv

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import java.net.HttpURLConnection
import java.net.URL

data class TvProfile(val guid: String, val remarks: String)

data class TvProfileSnapshot(
    val profiles: List<TvProfile> = emptyList(),
    val selected: String? = null
) {
    fun selectedProfile(): TvProfile? = profiles.firstOrNull { it.guid == selected }
}

/**
 * TV 界面与手机界面共用同一份 MMKV 配置，避免 TV 端产生第二套数据源。
 * 对应 v2ray4TV :tv 模块的 ProfileStore，底层换成 AngConfigManager / MmkvManager。
 */
object TvProfileStore {
    private const val MAX_SUBSCRIPTION_CHARS = 256 * 1024
    private val mainStorage by lazy {
        MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE)
    }

    fun load(): TvProfileSnapshot {
        val selected = mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER)
        val profiles = MmkvManager.decodeServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { TvProfile(guid, it.remarks) }
        }
        return TvProfileSnapshot(profiles, selected)
    }

    fun select(guid: String): Boolean {
        if (MmkvManager.decodeServerConfig(guid) == null) return false
        mainStorage?.encode(MmkvManager.KEY_SELECTED_SERVER, guid)
        return true
    }

    fun remove(guid: String) {
        MmkvManager.removeServer(guid)
    }

    /** 调用方负责线程。返回真正写入服务器的条数。 */
    fun add(text: String): Int {
        if (text.isBlank()) return 0
        var count = AngConfigManager.importBatchConfig(text, "", true)
        if (count <= 0) {
            val decoded = Utils.decode(text)
            if (decoded.isNotBlank()) {
                count = AngConfigManager.importBatchConfig(decoded, "", true)
            }
        }
        return count
    }

    fun isSubscriptionUrl(text: String): Boolean {
        val trimmed = text.trim()
        return '\n' !in trimmed && '\r' !in trimmed &&
            (trimmed.startsWith(AppConfig.HTTP_PROTOCOL, true) ||
                trimmed.startsWith(AppConfig.HTTPS_PROTOCOL, true))
    }

    /** 订阅地址需要先取回内容，否则 importConfig 只会登记订阅而不产生节点。 */
    fun downloadSubscription(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val out = StringBuilder()
                while (out.length < MAX_SUBSCRIPTION_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
