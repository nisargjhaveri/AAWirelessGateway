package com.nisargjhaveri.aagateway

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {
    private var mBluetoothHandler: BluetoothHandler? = null

    private var mErrorIcon: Drawable? = null
    private var mDoneIcon: Drawable? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mBluetoothHandler = BluetoothHandler(context, this)
    }

    override fun onDetach() {
        super.onDetach()

        mBluetoothHandler = null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<Preference>("pair_bluetooth")?.apply {
            intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        findPreference<Preference>("gateway_bt_mac")?.also { setBluetoothDevices(it) }
        findPreference<Preference>("client_bt_mac")?.also { setBluetoothDevices(it) }
        findPreference<Preference>("write_settings_permission")?.apply {
            intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        }
        findPreference<Preference>("system_alert_window_permission")?.apply {
            intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }
        findPreference<Preference>("bluetooth_permissions")?.apply {
            setOnPreferenceClickListener {
                mBluetoothHandler?.requestConnectPermissions {
                    updateSettingsState(context)
                }
                true
            }
        }
        findPreference<Preference>("manage_usb_permission")?.apply {
            setOnPreferenceClickListener {
                AlertDialog.Builder(context).apply {
                    title = "Manage USB Permission"
                    setMessage(
                        "You need to make this app an system app by moving it to /system/priv-app " +
                                "and ensure that MANAGE_USB permission is whitelisted. " +
                                "This would require your phone to be rooted."
                    )
                    setPositiveButton("Okay") { _, _ ->
                        // Do nothing
                    }
                    show()
                }
                true
            }
        }

        context?.also {
            updateSettingsState(it)
        }
    }

    override fun onResume() {
        super.onResume()

        context?.also {
            updateSettingsState(it)
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key.equals("gateway_bt_mac") || preference.key.equals("client_bt_mac")) {
            setBluetoothDevices(preference)
        }

        super.onDisplayPreferenceDialog(preference)
    }

    private fun updateSettingsState(context: Context) {
        if (mErrorIcon == null) {
            mErrorIcon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_error_outline_24)?.apply {
                setTint(resources.getColor(androidx.appcompat.R.color.error_color_material_light))
            }
        }

        if (mDoneIcon == null) {
            mDoneIcon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_done_24)
        }

        findPreference<Preference>("write_settings_permission")?.apply {
            isEnabled = !Settings.System.canWrite(context)
            summary = if (isEnabled) "Required to automatically connect to Wifi" else "Already granted"
            icon = if (isEnabled) mErrorIcon else mDoneIcon
        }

        findPreference<Preference>("system_alert_window_permission")?.apply {
            isEnabled = !Settings.canDrawOverlays(context)
            summary = if (isEnabled) "Required to start Android Auto" else "Already granted"
            icon = if (isEnabled) mErrorIcon else mDoneIcon
        }

        findPreference<Preference>("bluetooth_permissions")?.apply {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                isVisible = false
                return@apply
            }

            isEnabled = !(mBluetoothHandler?.hasConnectPermissions() ?: false)
            summary = if (isEnabled) "Required to connect with bluetooth" else "Already granted"
            icon = if (isEnabled) mErrorIcon else mDoneIcon
        }

        findPreference<Preference>("manage_usb_permission")?.apply {
            isEnabled = context.checkSelfPermission("android.permission.MANAGE_USB") != PackageManager.PERMISSION_GRANTED
            summary = if (isEnabled) "Required for fallback to USB Android Auto in gateway mode" else "Already granted"
            icon = if (isEnabled) mErrorIcon else mDoneIcon
        }
    }

    private fun setBluetoothDevices(preference: Preference) {
        mBluetoothHandler?.also { bluetoothHandler ->
            val devices = bluetoothHandler.getBondedDevices()

            val entries = devices.map { "${it.name} (${it.address})" }.toTypedArray()
            val entryValues = devices.map { it.address }.toTypedArray()

            when (preference) {
                is ListPreference -> {
                    preference.entries = entries
                    preference.entryValues = entryValues
                }
                is MultiSelectListPreference -> {
                    preference.entries = entries
                    preference.entryValues = entryValues
                }
                else -> {
                    // Cannot handle this preference
                }
            }
        }
    }

}