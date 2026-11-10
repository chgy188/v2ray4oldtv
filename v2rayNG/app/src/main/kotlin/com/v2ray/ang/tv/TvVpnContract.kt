package com.v2ray.ang.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils

/**
 * TV 界面的连接状态与命令契约，对应 v2ray4TV :tv 模块的 VpnContract。
 * 命令落到本项目的 V2RayServiceManager / MessageUtil，状态由原有服务广播回传。
 */
object TvVpnContract {
    const val ACTION_CONNECT = "com.v2ray.ang.tv.action.CONNECT"
    const val ACTION_DISCONNECT = "com.v2ray.ang.tv.action.DISCONNECT"
    const val ACTION_STATE = "com.v2ray.ang.tv.action.STATE"
    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"

    const val STATE_DISCONNECTED = "disconnected"
    const val STATE_CONNECTING = "connecting"
    const val STATE_CONNECTED = "connected"
    const val STATE_ERROR = "error"

    @Volatile
    var publishedState: String = STATE_DISCONNECTED
        private set

    @Volatile
    var publishedMessage: String? = null
        private set

    fun command(context: Context, action: String) {
        when (action) {
            ACTION_CONNECT -> {
                publish(context, STATE_CONNECTING)
                V2RayServiceManager.startV2Ray(context)
            }
            ACTION_DISCONNECT -> {
                Utils.stopVService(context)
            }
        }
    }

    fun publish(context: Context, state: String, message: String? = null) {
        record(state, message)
        context.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    fun currentState(): String = publishedState

    fun receiver(onState: (String, String?) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATE) return
            val state = intent.getStringExtra(EXTRA_STATE) ?: STATE_DISCONNECTED
            val message = intent.getStringExtra(EXTRA_MESSAGE)
            record(state, message)
            onState(state, message)
        }
    }

    /** 把服务进程原有的 MSG_STATE_* 广播映射成 TV 的四个状态。 */
    fun serviceStateReceiver(onState: (String, String?) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_SUCCESS, AppConfig.MSG_STATE_RUNNING -> STATE_CONNECTED
                AppConfig.MSG_STATE_START_FAILURE -> STATE_ERROR
                AppConfig.MSG_STATE_STOP_SUCCESS, AppConfig.MSG_STATE_NOT_RUNNING -> STATE_DISCONNECTED
                else -> return
            }
            record(state, null)
            onState(state, null)
        }
    }

    /** 服务进程只在启动后注册接收器，未连接时不会回复，此时保持默认的未连接状态。 */
    fun requestServiceState(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    private fun record(state: String, message: String?) {
        publishedState = state
        publishedMessage = message
    }
}
