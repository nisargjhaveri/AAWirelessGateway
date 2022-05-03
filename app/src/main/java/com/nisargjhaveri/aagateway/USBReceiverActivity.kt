package com.nisargjhaveri.aagateway

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.preference.PreferenceManager

class USBReceiverActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usbreceiver)
    }

    override fun onResume() {
        super.onResume()

        if (intent.action?.equals(UsbManager.ACTION_USB_ACCESSORY_ATTACHED) == true) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)

            if (preferences.getBoolean("is_gateway", false)) {
                // Start foreground service if gateway mode is enabled
                val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as UsbAccessory?

                accessory?.also { usbAccessory ->
                    val i = Intent(this, AAGatewayService::class.java)
                    i.putExtra(UsbManager.EXTRA_ACCESSORY, usbAccessory)
                    this.startForegroundService(i)
                }
            }
            else {
                // Gateway mode is not enabled, open settings
                this.startActivity(Intent(this, MainActivity::class.java))
            }
        }

        finish()
    }
}