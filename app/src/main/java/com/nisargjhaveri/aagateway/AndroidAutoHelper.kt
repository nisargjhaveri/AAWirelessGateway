package com.nisargjhaveri.aagateway

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

object AndroidAutoHelper {
    private const val ACTION_START_USB_ACCESSORY = "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"

    private const val PACKAGE_NAME_PROJECTION_GEARHEAD = "com.google.android.projection.gearhead"
    private const val CLASS_NAME_CARSERVICE_FIRSTACTIVITY = "com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl"

    private const val PACKAGE_NAME_ANDROID_GMS = "com.google.android.gms"
    private const val CLASS_NAME_GMS_CAR_FIRSTACTIVITY = "com.google.android.gms.car.FirstActivity"

    // This requires that this app is a system app, and hidden api access is enabled
    fun startUsbAndroidAuto(context: Context, accessory: UsbAccessory): Boolean {
        getUsbAndroidAutoIntent(context, accessory)?.let { intent ->
            val uid = context.packageManager.getPackageUid(intent.`package`!!, PackageManager.GET_META_DATA)
            if (!grantUsbAccessoryPermission(accessory, uid)) {
                return false
            }

            // Start Android Auto
            context.startActivity(intent)
            return true
        }

        return false
    }

    private fun getUsbAndroidAutoIntent(context: Context, accessory: UsbAccessory): Intent? {
        Intent()
            .setAction(ACTION_START_USB_ACCESSORY)
            .setPackage(PACKAGE_NAME_PROJECTION_GEARHEAD)
            .setClassName(PACKAGE_NAME_PROJECTION_GEARHEAD, CLASS_NAME_CARSERVICE_FIRSTACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            .let { intent ->
                intent.resolveActivityInfo(context.packageManager, 0)?.let {
                    if (it.exported) {
                        return intent
                    }
                }
            }

        Intent()
            .setAction(ACTION_START_USB_ACCESSORY)
            .setPackage(PACKAGE_NAME_ANDROID_GMS)
            .setClassName(PACKAGE_NAME_ANDROID_GMS, CLASS_NAME_GMS_CAR_FIRSTACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            .let { intent ->
                intent.resolveActivityInfo(context.packageManager, 0)?.let {
                    if (it.exported) {
                        return intent
                    }
                }
            }

        return null
    }

    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi")
    private fun grantUsbAccessoryPermission(usbAccessory: UsbAccessory, uid: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/usb/IUsbManager")
            }

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
        finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.clearHiddenApiExemptions()
            }
        }
    }
}