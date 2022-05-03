package com.nisargjhaveri.aagateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresPermission
import com.android.dx.stock.ProxyBuilder
import java.lang.reflect.Method


// Based on https://github.com/aegis1980/WifiHotSpot/
class WifiHotspotHandler(context: Context) {
    private val TETHERING_WIFI = 0
    private val mContext = context

    private val mConnectivityManager: ConnectivityManager by lazy { mContext.applicationContext.getSystemService(ConnectivityManager::class.java) }
    private val mWifiManager: WifiManager by lazy { mContext.getSystemService(WifiManager::class.java) }

    private var mWasWifiEnabled = false

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun isTethered(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            isTetherActive()
        } else {
            isTetherActivePreOreo()
        }
    }

    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    fun start(callback: (success: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            if (!startTethering(callback)) {
                callback.invoke(false)
            }
        } else {
            callback.invoke(handleHotspotPreOreo(true))
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            stopTethering()
        } else {
            handleHotspotPreOreo(false)
        }
    }

    private fun isTetherActivePreOreo(): Boolean {
        val method: Method? = mWifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
        return method?.invoke(mWifiManager) as Boolean
    }

    private fun handleHotspotPreOreo(start: Boolean): Boolean {
        val method: Method? = mWifiManager.javaClass.getDeclaredMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        method?.also { method ->
            try {
                if (start) {
                    if (mWifiManager.isWifiEnabled) {
                        mWasWifiEnabled = true
                        mWifiManager.setWifiEnabled(false)
                    }

                    method.invoke(mWifiManager, null, true) // Activate tethering

                    return true
                }
                else {
                    method.invoke(mWifiManager, null, false) // Deactivate tethering

                    if (mWasWifiEnabled) {
                        mWifiManager.setWifiEnabled(true)
                    }

                    return true
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        return false
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
        try {
            val method: Method? = mConnectivityManager.javaClass.getDeclaredMethod("getTetheredIfaces")
            if (method == null) {
//                Log.e(TAG, "getTetheredIfaces is null")
            }
            else {
                val res = method.invoke(mConnectivityManager) as Array<*>
//                Log.d(TAG, "getTetheredIfaces invoked")
//                Log.d(TAG, Arrays.toString(res))
                if (res.isNotEmpty()) {
                    return true
                }
            }
        } catch (e: java.lang.Exception) {
//            Log.e(TAG, "Error in getTetheredIfaces")
            e.printStackTrace()
        }
        return false
    }

    /**
     * This enables tethering using the ssid/password defined in Settings App>Hotspot & tethering
     * Does not require app to have system/privileged access
     * Credit: Vishal Sharma - https://stackoverflow.com/a/52219887
     */
    private fun startTethering(callback: (success: Boolean) -> Unit): Boolean {
        // On Pie if we try to start tethering while it is already on, it will
        // be disabled. This is needed when startTethering() is called programmatically.
        if (isTetherActive()) {
//            Log.d(TAG, "Tether already active, returning")
            callback.invoke(true)
            return true
        }

        val outputDir = mContext.codeCacheDir
        val proxy: Any = try {
            ProxyBuilder.forClass(getOnStartTetheringCallbackClass())
                .dexCache(outputDir).handler { proxy, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> callback(true)
                        "onTetheringFailed" -> callback(false)
                        else -> ProxyBuilder.callSuper(proxy, method, args)
                    }
                    null
                }.build()
        } catch (e: java.lang.Exception) {
//            Log.e(TAG, "Error in enableTethering ProxyBuilder")
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
//                Log.e(TAG, "startTetheringMethod is null")
            } else {
                method.invoke(
                    mConnectivityManager,
                    TETHERING_WIFI,
                    false,
                    proxy,
                    null
                )
//                Log.d(TAG, "startTethering invoked")
            }
            return true
        } catch (e: java.lang.Exception) {
//            Log.e(TAG, "Error in enableTethering")
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
//                Log.e(TAG, "stopTetheringMethod is null")
            } else {
                method.invoke(mConnectivityManager, TETHERING_WIFI)
//                Log.d(TAG, "stopTethering invoked")
            }
        } catch (e: java.lang.Exception) {
//            Log.e(TAG, "stopTethering error: $e")
            e.printStackTrace()
        }
    }

    @SuppressLint("PrivateApi")
    private fun getOnStartTetheringCallbackClass(): Class<*>? {
        try {
            return Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
        } catch (e: ClassNotFoundException) {
//            Log.e(TAG, "OnStartTetheringCallbackClass error: $e")
            e.printStackTrace()
        }
        return null
    }
}