package com.nisargjhaveri.aagateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.core.app.ActivityCompat


class WifiClientHandler(context: Context, activityResultCaller: ActivityResultCaller?) {
    private val mContext = context
    private val mActivityResultCaller = activityResultCaller

    private var mIsConnected = false

    private val mConnectivityManager: ConnectivityManager by lazy { mContext.getSystemService(ConnectivityManager::class.java) }
    private val mWifiManager: WifiManager by lazy { mContext.getSystemService(WifiManager::class.java) }

    private var mSsid: String? = null
    private var mConnectCallback: ((success: Boolean, msg: String?, network: Network?, wifiInfo: WifiInfo?) -> Unit)? = null
    private var mLostCallback: (() -> Unit)? = null
    private val mNetworkCallback = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)

            if (!mIsConnected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Do nothing, handle in onCapabilitiesChanged
                }
                else if (mWifiManager.connectionInfo.ssid == mSsid && mWifiManager.connectionInfo.supplicantState == SupplicantState.COMPLETED) {
                    handleConnected(network, mWifiManager.connectionInfo)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)

            if (!mIsConnected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val wifiInfo: WifiInfo = if (networkCapabilities.transportInfo != null && networkCapabilities.transportInfo is WifiInfo) {
                        networkCapabilities.transportInfo as WifiInfo
                    } else {
                        mWifiManager.connectionInfo
                    }
                    handleConnected(network, wifiInfo)
                }
                else if (mWifiManager.connectionInfo.ssid == mSsid && mWifiManager.connectionInfo.supplicantState == SupplicantState.COMPLETED) {
                    handleConnected(network, mWifiManager.connectionInfo)
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

    fun onLost(callback: () -> Unit) {
        mLostCallback = callback
    }

    fun connect(ssid: String, bssid: String, passphrase: String, callback: (success: Boolean, msg: String?, network: Network?, wifiInfo: WifiInfo?) -> Unit) {
        if (mIsConnected) {
            disconnect()
        }

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
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setBssid(MacAddress.fromString(bssid))
                .setWpa2Passphrase(passphrase)
                .build()

            request.setNetworkSpecifier(specifier)
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

        mConnectivityManager.requestNetwork(request.build(), mNetworkCallback, 15000)
    }

    private fun handleConnected(network: Network, wifiInfo: WifiInfo) {
        mIsConnected = true

        // Successfully connected
        mConnectCallback?.invoke(true, null, network, wifiInfo)
    }

    fun disconnect() {
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback)
        mIsConnected = false
    }
}