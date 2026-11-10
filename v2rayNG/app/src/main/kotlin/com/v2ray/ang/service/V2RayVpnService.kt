package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ERoutingMode
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlin.jvm.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicInteger

class V2RayVpnService : VpnService(), ServiceControl {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "26.26.26.1"
        private const val PRIVATE_VLAN4_ROUTER = "26.26.26.2"
        private const val PRIVATE_VLAN6_CLIENT = "da26:2626::1"
        private const val PRIVATE_VLAN6_ROUTER = "da26:2626::2"
        private const val TUN2SOCKS = "libtun2socks.so"
        private const val TUN2SOCKS_MAX_RESTART = 3
        private const val TUN2SOCKS_RESTART_DELAY_MS = 500L
        private const val TUN2SOCKS_STABLE_MS = 5000L
        private const val TUN2SOCKS_LOG_LINES = 10
    }

    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile private var isRunning = false
    @Volatile private var tun2socksRestartCount = 0
    @Volatile private var startupFailureReported = false
    private val tun2socksGeneration = AtomicInteger(0)

    //val fd: Int get() = mInterface.fd
    private lateinit var process: Process

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
    }

    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        stopV2Ray()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        V2RayServiceManager.cancelNotification()
    }

    private fun setup() {
        val prepare = prepare(this)
        if (prepare != null) {
            return
        }

        // Reset the failure state for every start attempt.
        tun2socksRestartCount = 0
        startupFailureReported = false

        // If the old interface has exactly the same parameters, use it!
        // Configure a builder while parsing the parameters.
        val builder = Builder()
        //val enableLocalDns = defaultDPreference.getPrefBoolean(AppConfig.PREF_LOCAL_DNS_ENABLED, false)

        val routingMode = settingsStorage?.decodeString(AppConfig.PREF_ROUTING_MODE) ?: ERoutingMode.GLOBAL_PROXY.value

        builder.setMtu(VPN_MTU)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        //builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        if (routingMode == ERoutingMode.BYPASS_LAN.value || routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value) {
            resources.getStringArray(R.array.bypass_private_ip_address).forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
            if (routingMode == ERoutingMode.BYPASS_LAN.value || routingMode == ERoutingMode.BYPASS_LAN_MAINLAND.value) {
                builder.addRoute("2000::", 3) //currently only 1/8 of total ipV6 is in use
            } else {
                builder.addRoute("::", 0)
            }
        }

        if (settingsStorage?.decodeBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        } else {
            Utils.getVpnDnsServers()
                    .forEach {
                        if (Utils.isPureIpAddress(it)) {
                            builder.addDnsServer(it)
                        }
                    }
        }

        builder.setSession(V2RayServiceManager.currentConfig?.remarks.orEmpty())

        if (settingsStorage?.decodeBool(AppConfig.PREF_PER_APP_PROXY) == true) {
            val apps = settingsStorage?.decodeStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            val bypassApps = settingsStorage?.decodeBool(AppConfig.PREF_BYPASS_APPS) ?: false
            apps?.forEach {
                try {
                    if (bypassApps)
                        builder.addDisallowedApplication(it)
                    else
                        builder.addAllowedApplication(it)
                } catch (e: PackageManager.NameNotFoundException) {
                    //Logger.d(e)
                }
            }
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Create a new interface using the builder and save the parameters.
        try {
            mInterface = builder.establish()!!
            isRunning = true
            runTun2socks()
        } catch (e: Exception) {
            // non-nullable lateinit var
            e.printStackTrace()
            reportStartupFailure("VPN interface establish failed: $e")
        }
    }

    private fun runTun2socks() {
        val socksPort = Utils.parseInt(settingsStorage?.decodeString(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
        val cmd = arrayListOf(File(applicationContext.applicationInfo.nativeLibraryDir, TUN2SOCKS).absolutePath,
                "--netif-ipaddr", PRIVATE_VLAN4_ROUTER,
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "127.0.0.1:${socksPort}",
                "--tunmtu", VPN_MTU.toString(),
                "--sock-path", "sock_path",//File(applicationContext.filesDir, "sock_path").absolutePath,
                "--enable-udprelay",
                "--loglevel", "notice")

        if (settingsStorage?.decodeBool(AppConfig.PREF_PREFER_IPV6) == true) {
            cmd.add("--netif-ip6addr")
            cmd.add(PRIVATE_VLAN6_ROUTER)
        }
        if (settingsStorage?.decodeBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
            val localDnsPort = Utils.parseInt(settingsStorage?.decodeString(AppConfig.PREF_LOCAL_DNS_PORT), AppConfig.PORT_LOCAL_DNS.toInt())
            cmd.add("--dnsgw")
            cmd.add("127.0.0.1:${localDnsPort}")
        }
        Log.d(packageName, cmd.toString())

        val generation = tun2socksGeneration.incrementAndGet()
        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            val tun2socksProcess = proBuilder
                    .directory(applicationContext.filesDir)
                    .start()
            process = tun2socksProcess
            Thread(Runnable {
                val startedAt = System.currentTimeMillis()
                val output = drainProcessOutput(tun2socksProcess)
                val exitCode = tun2socksProcess.waitFor()
                val aliveMs = System.currentTimeMillis() - startedAt
                if (exitCode != 0) {
                    Log.e(packageName, "$TUN2SOCKS exited abnormally (code=$exitCode, alive=${aliveMs}ms): $output")
                } else {
                    Log.d(packageName, "$TUN2SOCKS exited (code=0, alive=${aliveMs}ms): $output")
                }

                if (isRunning) {
                    if (aliveMs >= TUN2SOCKS_STABLE_MS) {
                        tun2socksRestartCount = 0
                    } else {
                        tun2socksRestartCount += 1
                    }
                    if (tun2socksRestartCount >= TUN2SOCKS_MAX_RESTART) {
                        reportStartupFailure("$TUN2SOCKS exited $tun2socksRestartCount times in a row (last code=$exitCode): $output")
                    } else {
                        Log.d(packageName, "$TUN2SOCKS restart $tun2socksRestartCount/$TUN2SOCKS_MAX_RESTART")
                        try {
                            Thread.sleep(TUN2SOCKS_RESTART_DELAY_MS)
                        } catch (ignored: InterruptedException) {
                        }
                        if (isRunning) {
                            runTun2socks()
                        }
                    }
                }
            }).start()
            Log.d(packageName, tun2socksProcess.toString())

            sendFd(generation)
        } catch (e: Exception) {
            Log.d(packageName, e.toString())
            reportStartupFailure("$TUN2SOCKS start failed: $e")
        }
    }

    private fun drainProcessOutput(proc: Process): String {
        val tail = mutableListOf<String>()
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (tail.size >= TUN2SOCKS_LOG_LINES) {
                        tail.removeAt(0)
                    }
                    tail.add(line)
                }
            }
        } catch (e: Exception) {
            Log.d(packageName, "read $TUN2SOCKS output failed: $e")
        }
        return tail.joinToString(" | ")
    }

    private fun reportStartupFailure(reason: String) {
        val shouldReport = synchronized(this) {
            if (startupFailureReported) {
                false
            } else {
                startupFailureReported = true
                isRunning = false
                true
            }
        }
        if (!shouldReport) return
        Log.e(packageName, "startup failed: $reason")
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
        stopV2Ray()
    }

    private fun sendFd(generation: Int) {
        val fd = mInterface.fileDescriptor
        val path = File(applicationContext.filesDir, "sock_path").absolutePath
        Log.d(packageName, path)

        GlobalScope.launch(Dispatchers.IO) {
            var tries = 0
            while (isRunning && generation == tun2socksGeneration.get()) {
                try {
                    Thread.sleep(50L shl tries)
                    Log.d(packageName, "sendFd tries: $tries")
                    LocalSocket().use { localSocket ->
                        localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                        localSocket.setFileDescriptorsForSend(arrayOf(fd))
                        localSocket.outputStream.write(42)
                    }
                    Log.d(packageName, "sendFd succeeded")
                    break
                } catch (e: Exception) {
                    Log.d(packageName, e.toString())
                    if (generation != tun2socksGeneration.get() || !isRunning) break
                    if (tries > 5) {
                        reportStartupFailure("sendFd failed after ${tries + 1} attempts: $e")
                        break
                    }
                    tries += 1
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2RayServiceManager.startV2rayPoint()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    private fun stopV2Ray(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        try {
            Log.d(packageName, "tun2socks destroy")
            process.destroy()
        } catch (e: Exception) {
            Log.d(packageName, e.toString())
        }

        V2RayServiceManager.stopV2rayPoint()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                mInterface.close()
            } catch (ignored: Exception) {
                // ignored
            }
        }
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setup()
    }

    override fun stopService() {
        stopV2Ray(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase,  Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }
}
