package com.v2ray.ang.tv

/** 连接成功后是否需要退回桌面，规则与 v2ray4TV :tv 模块一致。 */
object ConnectTransition {
    fun shouldMoveToBack(previousState: String, state: String): Boolean =
        previousState != TvVpnContract.STATE_CONNECTED && state == TvVpnContract.STATE_CONNECTED
}
