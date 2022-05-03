package com.nisargjhaveri.aagateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Network
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.*


class AAWirelessClientService : Service() {
    companion object {
        private const val LOG_TAG = "AAService"

        private const val NOTIFICATION_CHANNEL_ID = "default"
        private const val NOTIFICATION_ID = 2
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

        val wifiClientHandler = WifiClientHandler(this, null)

        if (ssid == null && bssid == null && password == null) {
            stopService("Wifi settings not found")
            return START_STICKY
        }

        wifiClientHandler.onLost {
            stopService("Wifi connection lost")
        }

        updateNotification("Connecting to gateway wifi")
        wifiClientHandler.connect(ssid!!, bssid!!, password!!) { success, msg, network, wifiInfo ->
            if (success) {
                Thread() {
                    kotlin.run {
                        val addressInt = getSystemService(WifiManager::class.java).dhcpInfo.gateway
                        val address = "%d.%d.%d.%d".format(null,
                            addressInt and 0xff,
                            addressInt shr 8 and 0xff,
                            addressInt shr 16 and 0xff,
                            addressInt shr 24 and 0xff)

                        connectAAWireless(address, network, wifiInfo)

                        updateNotification("Started Android Auto")
                    }
                }.start()
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

    private fun connectAAWireless(address: String, network: Network?, wifiInfo: WifiInfo?) {
        val CLASS_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
        val PACKAGE_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.projection.gearhead"
        val PARAM_HOST_ADDRESS_EXTRA_NAME = "PARAM_HOST_ADDRESS"
        val PARAM_SERVICE_PORT_EXTRA_NAME = "PARAM_SERVICE_PORT"
        val PARAM_SERVICE_WIFI_NETWORK_EXTRA_NAME = "PARAM_SERVICE_WIFI_NETWORK"
        val PARAM_WIFI_INFO_EXTRA_NAME = "wifi_info"

        val intent = Intent()
        intent.setClassName(PACKAGE_NAME_ANDROID_AUTO_WIRELESS, CLASS_NAME_ANDROID_AUTO_WIRELESS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(PARAM_HOST_ADDRESS_EXTRA_NAME, address)
        intent.putExtra(PARAM_SERVICE_PORT_EXTRA_NAME, 5288)
        intent.putExtra(PARAM_SERVICE_WIFI_NETWORK_EXTRA_NAME, network)
        intent.putExtra(PARAM_WIFI_INFO_EXTRA_NAME, wifiInfo)
        this.startActivity(intent)
    }
}