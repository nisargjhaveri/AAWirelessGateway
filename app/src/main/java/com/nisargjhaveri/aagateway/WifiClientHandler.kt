package com.nisargjhaveri.aagateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat


class WifiClientHandler(context: Context, activityResultCaller: ActivityResultCaller?) {
    private val mContext = context
    private val mActivityResultCaller = activityResultCaller

    private var mIsConnecting = false
    private var mIsConnected = false
    private var mIsSpecificRequest = false

    private val mConnectivityManager: ConnectivityManager by lazy { mContext.getSystemService(ConnectivityManager::class.java) }
    private val mWifiManager: WifiManager by lazy { mContext.getSystemService(WifiManager::class.java) }

    private var mSsid: String? = null
    private var mConnectCallback: ((success: Boolean, msg: String?, network: Network?, wifiInfo: WifiInfo?) -> Unit)? = null
    private var mLostCallback: (() -> Unit)? = null

    private var mRequestPermissionsCallback: ((Boolean) -> Unit)? = null
    private val mRequestPermissionsLauncher = mActivityResultCaller?.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { isGranted: Map<String, Boolean> ->
        val callback = mRequestPermissionsCallback
        mRequestPermissionsCallback = null

        if (isGranted.all { e -> e.value }) {
            callback?.invoke(true)
        }
        else {
            callback?.invoke(false)
        }
    }

    private inner class NetworkCallback: ConnectivityManager.NetworkCallback {
        constructor(): super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int): super(flags)

        override fun onAvailable(network: Network) {
            super.onAvailable(network)

            if (!mIsConnected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Do nothing, handle in onCapabilitiesChanged
                }
                else if (mWifiManager.connectionInfo.ssid == "\"$mSsid\"" && mWifiManager.connectionInfo.supplicantState == SupplicantState.COMPLETED) {
                    handleConnected(network, mWifiManager.connectionInfo)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)

            if (!mIsConnected) {
                val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkCapabilities.transportInfo != null && networkCapabilities.transportInfo is WifiInfo) {
                        networkCapabilities.transportInfo as WifiInfo
                    } else {
                        mWifiManager.connectionInfo
                    }

                if (mIsSpecificRequest
                    || (wifiInfo.ssid == "\"$mSsid\"" && wifiInfo.supplicantState == SupplicantState.COMPLETED))
                {
                    handleConnected(network, wifiInfo)
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)

            // Clean up
            disconnect()

            // Notify network lost
            mLostCallback?.invoke()
        }

        override fun onUnavailable() {
            super.onUnavailable()

            // Clean up
            disconnect()

            // Notify connection failed
            mConnectCallback?.invoke(false, "Wifi connection failed", null, null)
        }
    }
    private val mNetworkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) else NetworkCallback()

    fun onLost(callback: () -> Unit) {
        mLostCallback = callback
    }

    fun connect(ssid: String, passphrase: String, bssid: String?, timeoutMs: Int, callback: (success: Boolean, msg: String?, network: Network?, wifiInfo: WifiInfo?) -> Unit) {
        if (mIsConnected) {
            disconnect()
        }

        mIsSpecificRequest = false
        mSsid = ssid
        mConnectCallback = callback

        if (!mWifiManager.isWifiEnabled) {
            mConnectCallback?.invoke(false, "Wifi not enabled", null, null)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (bssid != null) {
                mIsSpecificRequest = true
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setBssid(MacAddress.fromString(bssid))
                    .setWpa2Passphrase(passphrase)
                    .build()

                request.setNetworkSpecifier(specifier)
            }
            else {
                mWifiManager.addNetworkSuggestions(
                    listOf(
                        WifiNetworkSuggestion.Builder().run {
                            setSsid(ssid)
                            setWpa2Passphrase(passphrase)
                            build()
                        }
                    )
                )

                // if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                //    // Could not add the suggestion
                // }

                mWifiManager.startScan()
            }
        }
        else {
            var networkId = -1
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                networkId = mWifiManager.configuredNetworks.let {
                    it.firstOrNull { it.SSID.trim('"') == ssid.trim('"') }?.networkId ?: -1
                }
            }

            if (networkId == -1) {
                val wifiConfiguration = WifiConfiguration()
                wifiConfiguration.SSID = "\"$ssid\""
                wifiConfiguration.preSharedKey = "\"$passphrase\""

                networkId = mWifiManager.addNetwork(wifiConfiguration)
            }

            if (networkId != -1) {
                if (mWifiManager.connectionInfo.networkId != networkId) {
                    mWifiManager.disconnect()
                    mWifiManager.enableNetwork(networkId, true)
                    mWifiManager.reconnect()
                }
            }
            else {
                // Failed to find or add network
            }
        }

        mIsConnecting = true
        mConnectivityManager.requestNetwork(request.build(), mNetworkCallback, timeoutMs)

        Handler(Looper.getMainLooper()).postDelayed({
            if (mIsConnecting && !mIsConnected) {
                mConnectCallback?.invoke(false, "Wifi connection failed", null, null)
            }
        }, timeoutMs.toLong())
    }

    private fun handleConnected(network: Network, wifiInfo: WifiInfo) {
        mIsConnecting = false
        mIsConnected = true

        // Successfully connected
        mConnectCallback?.invoke(true, null, network, wifiInfo)
    }

    fun disconnect() {
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback)
        mIsConnecting = false
        mIsConnected = false
    }

    fun hasLocationPermissions(): Boolean {
        return mContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && mContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || mContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissions(callback: ((success: Boolean) -> Unit)?) {
        mRequestPermissionsLauncher?.let {
            mRequestPermissionsCallback = callback
            it.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun requestBackgroundLocationPermissions(callback: ((success: Boolean) -> Unit)?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback?.invoke(true)
            return
        }

        mRequestPermissionsLauncher?.let {
            mRequestPermissionsCallback = callback
            it.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
}