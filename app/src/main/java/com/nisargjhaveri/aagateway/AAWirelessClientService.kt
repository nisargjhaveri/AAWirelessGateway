package com.nisargjhaveri.aagateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Network
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.preference.PreferenceManager
import java.lang.Exception
import java.net.*


class AAWirelessClientService : Service() {
    companion object {
        private const val LOG_TAG = "AAService"

        private const val NOTIFICATION_CHANNEL_ID = "default"
        private const val NOTIFICATION_ID = 2

        private const val POWER_SAVE_MODE = 1
        private const val INSUFFICIENT_BATTERY = 2
    }

    private var mRunning = false

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "General", NotificationManager.IMPORTANCE_DEFAULT)
        notificationChannel.setSound(null, null)
        notificationChannel.enableVibration(false)
        notificationChannel.enableLights(false)
        notificationManager.createNotificationChannel(notificationChannel)
    }

    private fun updateNotification(msg: String) {
        Log.i(LOG_TAG, "Notification updated: $msg")
        val notificationBuilder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(msg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (mRunning) return START_STICKY

        updateNotification("Started")

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)

        val ssid = preferences.getString("gateway_wifi_ssid", null)
        val bssid = preferences.getString("gateway_wifi_bssid", null)
        val password = preferences.getString("gateway_wifi_password", null)

        if (ssid == null && bssid == null && password == null) {
            stopService("Wifi settings not found")
            return START_STICKY
        }

        val connectionBatteryLimit = preferences.getInt("connection_battery_limit", 0)
        val connectInPowerSaveMode = preferences.getBoolean("connect_in_power_save_mode", false)

        var connectionRejectionReason = 0

        if (!connectInPowerSaveMode && getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) {
            connectionRejectionReason = connectionRejectionReason or POWER_SAVE_MODE
        }

        if (connectionBatteryLimit > 0
            && (getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) < connectionBatteryLimit)
        {
            connectionRejectionReason = connectionRejectionReason or INSUFFICIENT_BATTERY
        }

        val wifiClientHandler = WifiClientHandler(this, null)

        wifiClientHandler.onLost {
            stopService("Wifi connection lost")
        }

        updateNotification("Connecting to gateway wifi")
        wifiClientHandler.connect(ssid!!, bssid!!, password!!) { success, msg, network, wifiInfo ->
            if (success) {
                val addressInt = getSystemService(WifiManager::class.java).dhcpInfo.gateway
                val address = "%d.%d.%d.%d".format(null,
                    addressInt and 0xff,
                    addressInt shr 8 and 0xff,
                    addressInt shr 16 and 0xff,
                    addressInt shr 24 and 0xff)

                connectControlChannel(address, network, connectionRejectionReason) {
                    if (connectionRejectionReason > 0) {
                        wifiClientHandler.disconnect()
                        stopService("Rejected connection with reason $connectionRejectionReason")
                    }
                }

                if (connectionRejectionReason == 0) {
                    connectAAWireless(address, network, wifiInfo)
                    updateNotification("Started Android Auto")
                }
                else {
                    updateNotification("Rejecting connection with reason $connectionRejectionReason")
                }
            }
            else {
                stopService(msg ?: "Wifi connection failed")
            }
        }

        return START_STICKY
    }

    private fun stopService(msg: String) {
        updateNotification(msg)
        stopForeground(false)
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        mRunning = false
    }

    private fun connectControlChannel(address: String, network: Network?, connectionRejectionReason: Int, callback: () -> Unit) {
        Thread {
            kotlin.run {
                try {
                    val socket = Socket()
                    network?.bindSocket(socket)

                    socket.connect(InetSocketAddress(address, 5287))

                    socket.getOutputStream().write(connectionRejectionReason)
                    socket.close()

                    Handler(mainLooper).post(callback)
                }
                catch (e: Exception) {
                    Log.e(LOG_TAG, "Error in handshake: ${e.message}")
                }
            }
        }.start()
    }

    private fun connectAAWireless(address: String, network: Network?, wifiInfo: WifiInfo?) {
        val PACKAGE_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.projection.gearhead"
        val CLASS_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
        val PARAM_HOST_ADDRESS_EXTRA_NAME = "PARAM_HOST_ADDRESS"
        val PARAM_SERVICE_PORT_EXTRA_NAME = "PARAM_SERVICE_PORT"
        val PARAM_SERVICE_WIFI_NETWORK_EXTRA_NAME = "PARAM_SERVICE_WIFI_NETWORK"
        val PARAM_WIFI_INFO_EXTRA_NAME = "wifi_info"

        val intent = Intent()
            .setClassName(PACKAGE_NAME_ANDROID_AUTO_WIRELESS, CLASS_NAME_ANDROID_AUTO_WIRELESS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PARAM_HOST_ADDRESS_EXTRA_NAME, address)
            .putExtra(PARAM_SERVICE_PORT_EXTRA_NAME, 5288)
            .putExtra(PARAM_SERVICE_WIFI_NETWORK_EXTRA_NAME, network)
            .putExtra(PARAM_WIFI_INFO_EXTRA_NAME, wifiInfo)

        this.startActivity(intent)
    }
}