package com.nisargjhaveri.aagateway

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class USBReceiverActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usbreceiver)
    }

    override fun onResume() {
        super.onResume()

        val intent: Intent = intent
        val i = Intent(this, AAGatewayService::class.java)

        if (intent.action?.equals(ACTION_USB_ACCESSORY_ATTACHED) == true) {
            (intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as UsbAccessory?)?.also { usbAccessory ->
                i.putExtra(UsbManager.EXTRA_ACCESSORY, usbAccessory)
                this.startForegroundService(i)
            }
            finish()
        }
    }
}