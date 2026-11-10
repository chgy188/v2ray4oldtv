package com.v2ray.ang.tv

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonPrimitive
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

/**
 * 唯一承载配置内容的页面：已保存节点列表、删除，以及随页面打开的短时配对端点。
 * TV 展示配对 URL 的二维码，手机浏览器打开后把分享链接 POST 回局域网；
 * 二维码下方的按钮是第二条导入入口，供已把配置放进系统剪贴板的盒子使用。
 */
class ConfigActivity : Activity() {
    private val scope = UiScope()
    private lateinit var profileList: ListView
    private lateinit var emptyText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var pairingText: TextView
    private lateinit var clipboardButton: Button

    private var pairing: PairingServer? = null
    private var snapshot = TvProfileSnapshot()
    private var checkedGuid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        profileList = findViewById(R.id.profileList)
        emptyText = findViewById(R.id.emptyText)
        qrImage = findViewById(R.id.qrImage)
        pairingText = findViewById(R.id.pairingText)
        clipboardButton = findViewById(R.id.clipboardButton)

        clipboardButton.setOnClickListener { importFromClipboard() }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { deleteChecked() }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        profileList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            checkedGuid = snapshot.profiles.getOrNull(position)?.guid
        }
        clipboardButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        startPairing()
    }

    override fun onStop() {
        stopPairing()
        super.onStop()
    }

    override fun onDestroy() {
        stopPairing()
        scope.close()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch({ TvProfileStore.load() }) { result ->
            snapshot = result.getOrDefault(TvProfileSnapshot())
            checkedGuid = checkedGuid?.takeIf { id -> snapshot.profiles.any { it.guid == id } }
            renderList()
        }
    }

    private fun renderList() {
        val labels = snapshot.profiles.map {
            it.remarks.takeIf { value -> value.isNotBlank() }
                ?: getString(R.string.tv_profile_unnamed)
        }
        profileList.adapter = ArrayAdapter(this, R.layout.item_profile, R.id.profileName, labels)
        profileList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        emptyText.visibility = if (labels.isEmpty()) View.VISIBLE else View.GONE
        val index = snapshot.profiles.indexOfFirst { it.guid == checkedGuid }
        if (index >= 0) profileList.setItemChecked(index, true)
    }

    private fun deleteChecked() {
        val guid = checkedGuid
        if (guid == null) {
            Toast.makeText(this, R.string.tv_action_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch({ TvProfileStore.remove(guid) }) {
            checkedGuid = null
            refresh()
        }
    }

    private fun startPairing() {
        stopPairing()
        val created = PairingSession.create()
        if (created == null) {
            pairingText.text = getString(R.string.tv_pairing_no_network)
            qrImage.setImageDrawable(null)
            return
        }
        val page = buildPage(created.token)
        val server = PairingServer(created.token, page, ::importFromPhone)
        val port = runCatching { server.start() }.getOrElse {
            pairingText.text = getString(R.string.tv_pairing_failed)
            return
        }
        pairing = server
        val url = created.url(port)
        pairingText.text = getString(R.string.tv_pairing_waiting)
        scope.launch({ Utils.createQRCode(url, QR_SIZE) }) { result ->
            result.getOrNull()?.let { qrImage.setImageBitmap(it) }
        }
    }

    private fun stopPairing() {
        pairing?.stop()
        pairing = null
    }

    /** 不需要键盘即可读取系统剪贴板；解析与磁盘写入都留在后台线程。 */
    private fun importFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = runCatching {
            manager?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.tv_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch({ parseAndStore(text) }) { result ->
            showImportResult(result.getOrDefault(0))
        }
    }

    /** 运行在 PairingServer 线程；解析与磁盘写入都不占用主线程。 */
    private fun importFromPhone(text: String): PairingServer.ImportResult {
        val count = runCatching { parseAndStore(text) }.getOrDefault(0)
        runOnUiThread { showImportResult(count) }
        return PairingServer.ImportResult(count > 0, count)
    }

    /** 两个导入入口共用，调用方负责线程。 */
    private fun parseAndStore(text: String): Int {
        val content = if (TvProfileStore.isSubscriptionUrl(text)) {
            runCatching { TvProfileStore.downloadSubscription(text.trim()) }.getOrNull().orEmpty()
        } else {
            text
        }
        return TvProfileStore.add(content)
    }

    private fun showImportResult(count: Int) {
        refresh()
        pairingText.text = if (count > 0) {
            getString(R.string.tv_pairing_received, count)
        } else {
            getString(R.string.tv_pairing_failed)
        }
    }

    /** 页面文案全部来自 Android 资源，跟随系统语言。 */
    private fun buildPage(token: String): String {
        val template = resources.openRawResource(R.raw.pairing_page)
            .bufferedReader().use { it.readText() }
        fun js(value: String) = JsonPrimitive(value).toString()
        return template
            .replace("__TITLE__", TextUtils.htmlEncode(getString(R.string.tv_html_title)))
            .replace("__NOTE__", TextUtils.htmlEncode(getString(R.string.tv_html_note)))
            .replace("__PLACEHOLDER__", TextUtils.htmlEncode(getString(R.string.tv_html_placeholder)))
            .replace("__SUBMIT__", TextUtils.htmlEncode(getString(R.string.tv_html_submit)))
            .replace("__EMPTY__", js(getString(R.string.tv_html_empty)))
            .replace("__SENDING__", js(getString(R.string.tv_html_sending)))
            .replace("__SUCCESS__", js(getString(R.string.tv_html_success)))
            .replace("__FAILURE__", js(getString(R.string.tv_html_failure)))
            .replace("__TOKEN__", token)
    }

    private companion object {
        const val QR_SIZE = 640
    }
}
