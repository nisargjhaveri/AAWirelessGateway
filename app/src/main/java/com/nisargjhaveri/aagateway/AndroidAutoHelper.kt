package com.nisargjhaveri.aagateway

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.IBinder
import java.lang.reflect.Method

object AndroidAutoHelper {
    private const val PACKAGE_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.projection.gearhead"
    private const val CLASS_NAME_ANDROID_AUTO_WIRELESS = "com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl"
    private const val ACTION_START_USB_ACCESSORY = "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"

    // This requires that this app is a system app, and hidden api access is enabled
    fun startUsbAndroidAuto(context: Context, accessory: UsbAccessory): Boolean {
        // Try to grant accessory permission to Android Auto
        val uid = context.packageManager.getPackageUid(PACKAGE_NAME_ANDROID_AUTO_WIRELESS, PackageManager.GET_META_DATA)
        if (!grantUsbAccessoryPermission(accessory, uid)) {
            return false
        }

        // Start Android Auto
        val intent = Intent()
            .setAction(ACTION_START_USB_ACCESSORY)
            .setClassName(PACKAGE_NAME_ANDROID_AUTO_WIRELESS, CLASS_NAME_ANDROID_AUTO_WIRELESS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)

        context.startActivity(intent)

        return true
    }

    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi")
    private fun grantUsbAccessoryPermission(usbAccessory: UsbAccessory, uid: Int): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod: Method = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.isAccessible = true
            val binder = getServiceMethod.invoke(null, Context.USB_SERVICE) as IBinder
            val iUsbManagerClass = Class.forName("android.hardware.usb.IUsbManager")
            val stubClass = Class.forName("android.hardware.usb.IUsbManager\$Stub")
            val asInterfaceMethod: Method = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.isAccessible = true
            val iUsbManager: Any? = asInterfaceMethod.invoke(null, binder)
            val grantAccessoryPermissionMethod: Method = iUsbManagerClass.getDeclaredMethod(
                "grantAccessoryPermission",
                UsbAccessory::class.java,
                Int::class.javaPrimitiveType
            )
            grantAccessoryPermissionMethod.isAccessible = true
            grantAccessoryPermissionMethod.invoke(iUsbManager, usbAccessory, uid)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}