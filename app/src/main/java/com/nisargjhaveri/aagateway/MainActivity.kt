package com.nisargjhaveri.aagateway

import android.content.Intent
import android.net.*
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("is_gateway", false)) {
//            startForegroundService(Intent(this, AAGatewayService::class.java))
//        }

//        supportFragmentManager
//            .beginTransaction()
//            .replace(R.id.settings_container, SettingsFragment())
//            .commit()


//        connectButton.setOnClickListener {
//            if (!mBluetoothHandler.hasPermissions()) {
//                log("Requesting BLUETOOTH_CONNECT permission")
//                if (shouldShowRequestPermissionRationale(BLUETOOTH_CONNECT)) {
//                    log("Permission request rationale dialog required")
//                }
//                mBluetoothHandler.requestPermissions { success ->
//                    log(if (success) "Bluetooth permission granted" else "Bluetooth permission not granted")
//                }
//            }
//            if (!mBluetoothHandler.isEnabled()) {
//                log("Bluetooth is not enabled")
//                mBluetoothHandler.setEnabled { success ->
//                    log(if (success) "Bluetooth successfully enabled" else "Failed to enable Bluetooth")
//                }
//            }
//            else {
//                val mac: String = "3C:28:6D:0E:FB:86"
//                mBluetoothHandler.connectDevice(mac) { l -> log(l) }
//            }

//            val wifiP2PHandler = WifiP2PHandler(this, this)
//            wifiP2PHandler.start()

//            if (connected) {
//                mWifiClientHandler.disconnect()
//                connected = false
//                log("Disconnecting wifi")
//
//                return@setOnClickListener
//            }
//
//            mWifiClientHandler.connect { success, msg ->
//                if (success) {
//                    connected = true
//                    log("Wifi connected")
//
//                    val wifiInfo = getSystemService(WifiManager::class.java).connectionInfo
//                    log("Wifi BSSID: ${wifiInfo.bssid}")
//                }
//                else {
//                    log("Wifi connection failed: $msg")
//                }
//            }

//            val hotspotHandler = WifiHotspotHandler(this)
//
//            if (hotspotHandler.isTethered()) {
//                hotspotHandler.stop()
//                log("Hotspot successfully stopped")
//            }
//            else {
//                hotspotHandler.start { success ->
//                    log(if (success) "Hotspot successfully started" else "Could not start hotspot")
//                }
//            }

//            startForegroundService(Intent(this, AAService::class.java))
//        }
    }

}
