package com.nisargjhaveri.aagateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import com.android.dx.stock.ProxyBuilder
import java.lang.RuntimeException
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.NetworkInterface

data class WifiHotspotInfo(
    var ssid: String,
    var passphrase: String,
    var bssid: String?,
    var ipAddress: String,
    var securityMode: WifiInfoRequestOuterClass.SecurityMode,
    var accessPointType: WifiInfoRequestOuterClass.AccessPointType,
)

// Based on https://github.com/aegis1980/WifiHotSpot/
class WifiHotspotHandler(context: Context) {
    companion object {
        private const val LOG_TAG = "AAService"

        private const val DEFAULT_IP_ADDRESS = "192.168.43.1"
        private const val POLL_INTERVAL_MS = 500L
        // How long to wait for the tethering to come up after it was requested
        private const val TETHER_TIMEOUT_MS = 20_000L
        // How long to wait for the tethered interface to get an IPv4 address
        private const val ADDRESS_TIMEOUT_MS = 5_000L
    }

    private val TETHERING_WIFI = 0
    private val mContext = context

    private val mConnectivityManager: ConnectivityManager by lazy { mContext.applicationContext.getSystemService(ConnectivityManager::class.java) }

    private lateinit var mSsid: String
    private lateinit var mPassphrase: String
    private var mBssid: String? = null

    // Only stop tethering in stop() if we were the ones who started it. Previously a
    // hotspot the user had enabled manually was switched off at the end of every session.
    private var mStartedByUs = false

    private val mHandler = Handler(Looper.getMainLooper())

    private fun log(message: String) {
        Log.d(LOG_TAG, "Hotspot: $message")
    }

    private fun logError(message: String) {
        Log.e(LOG_TAG, "Hotspot: $message")
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun isTethered(): Boolean {
        return isTetherActive()
    }

    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    fun start(ssid: String, passphrase: String, bssid: String?, callback: (success: Boolean, wifiHotspotInfo: WifiHotspotInfo?) -> Unit) {
        mSsid = ssid
        mPassphrase = passphrase
        mBssid = bssid

        log("Starting hotspot")

        if (!startTethering(callback)) {
            callback.invoke(false, null)
        }
    }

    fun stop() {
        mHandler.removeCallbacksAndMessages(null)

        if (mStartedByUs) {
            stopTethering()
            mStartedByUs = false
        }
        else {
            log("Hotspot was already active before we started, leaving it on")
        }
    }

    private fun getWifiHotspotInfo(): WifiHotspotInfo {
        log("Gathering Wifi Hotspot Info")

        val wifiHotspotInfo = WifiHotspotInfo(
            mSsid,
            mPassphrase,
            mBssid,
            DEFAULT_IP_ADDRESS,
            WifiInfoRequestOuterClass.SecurityMode.WPA2_PERSONAL,
            WifiInfoRequestOuterClass.AccessPointType.DYNAMIC
        )

        populateWifiHotspotAddresses(wifiHotspotInfo);

        return wifiHotspotInfo
    }

    /** Returns the IPv4 address of a tethered interface, if one is assigned yet. */
    private fun getTetheredIpv4Address(): String? {
        val ifaces = getTetheredIfaces().filterIsInstance<String>()
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface -> ifaces.any { it.equals(iface.name, true) } }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun populateWifiHotspotAddresses(wifiHotspotInfo: WifiHotspotInfo) {
        val ifaces = getTetheredIfaces().filterIsInstance<String>()

        var fallbackIpAddress: String? = null
        var fallbackHwAddress: String? = null

        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        for (networkInterface in networkInterfaces) {
            var ipAddress: String? = null

            val hwAddress = networkInterface.hardwareAddress?.joinToString(":") { byte ->
                String.format("%02X", byte)
            }

            for (inetAddress in networkInterface.inetAddresses) {
                // The phone gets this address in WifiStartRequest and connects to it over
                // IPv4; a site-local IPv6 address here would break the connection.
                if (inetAddress is Inet4Address && inetAddress.isSiteLocalAddress) {
                    ipAddress = inetAddress.hostAddress

                    fallbackIpAddress = ipAddress
                    fallbackHwAddress = hwAddress
                }
            }

            if (ifaces.any { it.equals(networkInterface.name, true) }) {
                log("Found tethered interface ${networkInterface.name}")

                ipAddress?.let { ipAddr ->
                    log("Using ip $ipAddr from interface ${networkInterface.name}")
                    wifiHotspotInfo.ipAddress = ipAddr

                    // Note: for apps targeting API 30+ Android returns no hardware address,
                    // so on Android 11+ the configured "Hotspot BSSID" is what gets used.
                    hwAddress?.let { hwAddr ->
                        log("Using bssid $hwAddr from interface ${networkInterface.name}")
                        wifiHotspotInfo.bssid = hwAddr
                    }

                    return
                }
            }
        }

        wifiHotspotInfo.ipAddress = fallbackIpAddress ?: wifiHotspotInfo.ipAddress
        // The fallback interface is a guess (could be the cellular or wifi client
        // interface), so never let its MAC override a BSSID the user configured.
        wifiHotspotInfo.bssid = wifiHotspotInfo.bssid ?: fallbackHwAddress

        log("Falling back to ip = ${wifiHotspotInfo.ipAddress} and bssid = ${wifiHotspotInfo.bssid}")

        return
    }

    /**
     * Checks where tethering is on.
     * This is determined by the getTetheredIfaces() method,
     * that will return an empty array if not devices are tethered
     *
     * @return true if a tethered device is found, false if not found
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun isTetherActive(): Boolean {
        val res = getTetheredIfaces()

        if (res.isNotEmpty()) {
            return true
        }

        return false
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun getTetheredIfaces(): Array<*> {
        try {
            val method: Method? = mConnectivityManager.javaClass.getDeclaredMethod("getTetheredIfaces")
            if (method == null) {
                throw RuntimeException("Cannot find getTetheredIfaces method in ConnectivityManager")
            }
            else {
                return method.invoke(mConnectivityManager) as Array<*>
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }

        return arrayOf<String>();
    }

    /**
     * Waits until tethering is active and the tethered interface has an address, then
     * invokes the callback. Previously this polled forever if tethering never came up,
     * and read the addresses before the interface was configured.
     */
    private fun callbackOnceTetherActive(
        callback: (success: Boolean, wifiHotspotInfo: WifiHotspotInfo?) -> Unit,
        startTime: Long = SystemClock.elapsedRealtime(),
        tetherActiveTime: Long? = null
    ) {
        val now = SystemClock.elapsedRealtime()

        if (!isTetherActive()) {
            if (now - startTime > TETHER_TIMEOUT_MS) {
                logError("Tethering did not become active in time")
                callback(false, null)
                return
            }
            mHandler.postDelayed({ callbackOnceTetherActive(callback, startTime, null) }, POLL_INTERVAL_MS)
            return
        }

        val activeSince = tetherActiveTime ?: now
        if (getTetheredIpv4Address() == null && now - activeSince < ADDRESS_TIMEOUT_MS) {
            mHandler.postDelayed({ callbackOnceTetherActive(callback, startTime, activeSince) }, POLL_INTERVAL_MS)
            return
        }

        callback(true, getWifiHotspotInfo())
    }

    /**
     * This enables tethering using the ssid/password defined in Settings App>Hotspot & tethering
     * Does not require app to have system/privileged access
     * Credit: Vishal Sharma - https://stackoverflow.com/a/52219887
     */
    private fun startTethering(callback: (success: Boolean, wifiHotspotInfo: WifiHotspotInfo?) -> Unit): Boolean {
        // On Pie if we try to start tethering while it is already on, it will
        // be disabled. This is needed when startTethering() is called programmatically.
        if (isTetherActive()) {
            log("Tether already active, nothing to do")
            mStartedByUs = false
            callback.invoke(true, getWifiHotspotInfo())
            return true
        }

        val outputDir = mContext.codeCacheDir
        val proxy: Any = try {
            ProxyBuilder.forClass(getOnStartTetheringCallbackClass())
                .dexCache(outputDir).handler { proxy, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> {
                            mStartedByUs = true
                            callbackOnceTetherActive(callback)
                        }
                        "onTetheringFailed" -> {
                            logError("onTetheringFailed")
                            callback(false, null)
                        }
                        else -> ProxyBuilder.callSuper(proxy, method, args)
                    }
                    null
                }.build()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            return false
        }
        val method: Method?
        try {
            method = mConnectivityManager.javaClass.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, getOnStartTetheringCallbackClass(),
                Handler::class.java
            )
            if (method == null) {
                throw RuntimeException("Cannot find startTetheringMethod method in ConnectivityManager")
            } else {
                method.invoke(
                    mConnectivityManager,
                    TETHERING_WIFI,
                    false,
                    proxy,
                    null
                )
            }
            return true
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun stopTethering() {
        try {
            val method: Method? = mConnectivityManager.javaClass.getDeclaredMethod(
                "stopTethering",
                Int::class.javaPrimitiveType
            )
            if (method == null) {
                throw RuntimeException("Cannot find stopTetheringMethod method in ConnectivityManager")
            } else {
                method.invoke(mConnectivityManager, TETHERING_WIFI)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("PrivateApi")
    private fun getOnStartTetheringCallbackClass(): Class<*>? {
        try {
            return Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
        }
        return null
    }
}