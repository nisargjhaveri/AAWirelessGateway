package com.nisargjhaveri.aagateway

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) {
            return
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

        if (preferences.getBoolean("is_wireless_client", false)
            && bluetoothDevice != null
            && bluetoothDevice.address.equals(preferences.getString("gateway_bt_mac", null), true)
        ) {
            context.startForegroundService(Intent(context, AAWirelessClientService::class.java))
        }
    }
}