package com.v2ray.ang.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream

/**
 * Leanback 主页：未连接时只有一个主控件，选中配置后立即连接；已连接时同一个控件变成一键断开。
 * 所有配置操作都在 ConfigActivity，界面与操作流程与 v2ray4TV :tv 的 MainActivity 一致。
 */
class TvMainActivity : Activity() {
    private val scope = UiScope()
    private lateinit var statusText: TextView
    private lateinit var primaryButton: Button
    private lateinit var configButton: Button

    private val stateReceiver = TvVpnContract.receiver { state, message -> onState(state, message) }
    private val serviceStateReceiver =
        TvVpnContract.serviceStateReceiver { state, message -> onState(state, message) }
    private var snapshot = TvProfileSnapshot()
    private var currentState = TvVpnContract.STATE_DISCONNECTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)
        statusText = findViewById(R.id.statusText)
        primaryButton = findViewById(R.id.primaryButton)
        configButton = findViewById(R.id.configButton)
        primaryButton.setOnClickListener { onPrimary() }
        configButton.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        primaryButton.requestFocus()
        copyAssets()
        migrateLegacy()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(stateReceiver, IntentFilter(TvVpnContract.ACTION_STATE))
        registerReceiver(serviceStateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY))
        TvVpnContract.requestServiceState(this)
        refresh()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(serviceStateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        scope.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            TvVpnContract.command(this, TvVpnContract.ACTION_CONNECT)
        } else {
            TvVpnContract.publish(
                this,
                TvVpnContract.STATE_ERROR,
                getString(R.string.tv_error_vpn_permission)
            )
        }
    }

    private fun refresh() {
        currentState = TvVpnContract.currentState()
        scope.launch({ TvProfileStore.load() }) { result ->
            snapshot = result.getOrDefault(TvProfileSnapshot())
            render()
        }
    }

    private fun onState(state: String, message: String?) {
        val previousState = currentState
        currentState = state
        render(message)
        // 遥控器用户连上后不需要停留在本页，退回桌面继续观看。
        if (ConnectTransition.shouldMoveToBack(previousState, state)) {
            moveTaskToBack(true)
        }
    }

    private fun render(message: String? = TvVpnContract.publishedMessage) {
        val connected = currentState == TvVpnContract.STATE_CONNECTED
        val name = snapshot.selectedProfile()?.remarks?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tv_profile_unnamed)
        statusText.text = when (currentState) {
            TvVpnContract.STATE_CONNECTED -> getString(R.string.tv_status_connected, name)
            TvVpnContract.STATE_CONNECTING -> getString(R.string.tv_status_connecting)
            TvVpnContract.STATE_ERROR -> getString(
                R.string.tv_status_error,
                message ?: getString(R.string.tv_error_core_generic)
            )
            else -> getString(R.string.tv_status_disconnected)
        }
        primaryButton.text = getString(
            if (connected) R.string.tv_action_disconnect else R.string.tv_action_select_and_connect
        )
        primaryButton.contentDescription = primaryButton.text
    }

    private fun onPrimary() {
        if (currentState == TvVpnContract.STATE_CONNECTED) {
            TvVpnContract.command(this, TvVpnContract.ACTION_DISCONNECT)
            currentState = TvVpnContract.STATE_DISCONNECTED
            render()
            return
        }
        if (snapshot.profiles.isEmpty()) {
            Toast.makeText(this, R.string.tv_no_profiles, Toast.LENGTH_LONG).show()
            return
        }
        // 用按钮 + 单选对话框代替 Spinner：D-pad 左右键会误改 Spinner 选中项并意外启动 VPN。
        val names = Array<CharSequence>(snapshot.profiles.size) { index ->
            snapshot.profiles[index].remarks.takeIf { it.isNotBlank() }
                ?: getString(R.string.tv_profile_unnamed)
        }
        val checked = snapshot.profiles.indexOfFirst { it.guid == snapshot.selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_dialog_select_profile)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                val guid = snapshot.profiles[which].guid
                scope.launch({ TvProfileStore.select(guid) }) { result ->
                    if (result.getOrDefault(false)) {
                        snapshot = snapshot.copy(selected = guid)
                        render()
                        startVpn()
                    }
                }
            }
            .setNegativeButton(R.string.tv_action_cancel, null)
            .show()
    }

    /** VpnService.prepare 需要 Activity result，平台回调在 API 21 上即可用。 */
    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            TvVpnContract.command(this, TvVpnContract.ACTION_CONNECT)
        }
    }

    /** geoip/geosite 由核心读取，旧入口不再打开时仍需在这里准备。 */
    private fun copyAssets() {
        val extFolder = Utils.userAssetPath(this)
        scope.launch({
            val geo = arrayOf("geosite.dat", "geoip.dat")
            assets.list("")
                ?.filter { geo.contains(it) }
                ?.filter { !File(extFolder, it).exists() }
                ?.forEach { name ->
                    val target = File(extFolder, name)
                    assets.open(name).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
        }) { result ->
            result.exceptionOrNull()?.let {
                Log.e(AppConfig.ANG_PACKAGE, "asset copy failed", it)
            }
        }
    }

    private fun migrateLegacy() {
        scope.launch({ AngConfigManager.migrateLegacyConfig(this@TvMainActivity) }) { }
    }

    private companion object {
        const val REQUEST_VPN = 1001
    }
}
