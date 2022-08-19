package com.nisargjhaveri.aagateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.android.dx.stock.ProxyBuilder
import java.lang.RuntimeException
import java.lang.reflect.Method
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
    }

    private val TETHERING_WIFI = 0
    private val mContext = context

    private val mConnectivityManager: ConnectivityManager by lazy { mContext.applicationContext.getSystemService(ConnectivityManager::class.java) }

    private lateinit var mSsid: String
    private lateinit var mPassphrase: String
    private var mBssid: String? = null

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
        stopTethering()
    }

    private fun getWifiHotspotInfo(): WifiHotspotInfo {
        log("Gathering Wifi Hotspot Info")

        val wifiHotspotInfo = WifiHotspotInfo(
            mSsid,
            mPassphrase,
            mBssid,
            "192.168.43.1",
            WifiInfoRequestOuterClass.SecurityMode.WPA2_PERSONAL,
            WifiInfoRequestOuterClass.AccessPointType.DYNAMIC
        )

        populateWifiHotspotAddresses(wifiHotspotInfo);

        return wifiHotspotInfo
    }

    private fun populateWifiHotspotAddresses(wifiHotspotInfo: WifiHotspotInfo) {
        val ifaces = getTetheredIfaces() as Array<String>

        var fallbackIpAddress: String? = null
        var fallbackHwAddress: String? = null

        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        for (networkInterface in networkInterfaces) {
            var ipAddress: String? = null

            val hwAddress = networkInterface.hardwareAddress?.joinToString(":") { byte ->
                String.format("%02X", byte)
            }

            for (inetAddress in networkInterface.inetAddresses) {
                if (inetAddress.isSiteLocalAddress) {
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

                    hwAddress?.let { hwAddr ->
                        log("Using bssid $hwAddr from interface ${networkInterface.name}")
                        wifiHotspotInfo.bssid = hwAddress
                    }

                    return
                }
            }
        }

        wifiHotspotInfo.ipAddress = fallbackIpAddress ?: wifiHotspotInfo.ipAddress
        wifiHotspotInfo.bssid = fallbackHwAddress ?: wifiHotspotInfo.bssid

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

    private fun callbackOnceTetherActive(callback: (success: Boolean, wifiHotspotInfo: WifiHotspotInfo?) -> Unit) {
        if (isTetherActive()) {
            callback(true, getWifiHotspotInfo())
        }
        else {
            Handler(Looper.getMainLooper()).postDelayed({
                callbackOnceTetherActive(callback)
            }, 500)
        }
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
            callback.invoke(true, getWifiHotspotInfo())
            return true
        }

        val outputDir = mContext.codeCacheDir
        val proxy: Any = try {
            ProxyBuilder.forClass(getOnStartTetheringCallbackClass())
                .dexCache(outputDir).handler { proxy, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> callbackOnceTetherActive(callback)
                        "onTetheringFailed" -> callback(false, null)
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