package com.nisargjhaveri.aagateway

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.*

class SettingsFragment : PreferenceFragmentCompat() {
    private var mBluetoothHandler: BluetoothHandler? = null
    private var mWifiClientHandler: WifiClientHandler? = null

    private var mErrorIcon: Drawable? = null
    private var mDoneIcon: Drawable? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mBluetoothHandler = BluetoothHandler(context, this)
        mWifiClientHandler = WifiClientHandler(context, this)
    }

    override fun onDetach() {
        super.onDetach()

        mBluetoothHandler = null
        mWifiClientHandler = null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<SwitchPreferenceCompat>("is_wireless_client")?.apply {
            setOnPreferenceChangeListener() { preference, enabled ->
                if (enabled is Boolean) {
                    setComponentEnabledForPreference(preference, enabled)
                }
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("is_gateway")?.apply {
            setOnPreferenceChangeListener() { preference, enabled ->
                if (enabled is Boolean) {
                    setComponentEnabledForPreference(preference, enabled)
                }
                true
            }
        }
        findPreference<EditTextPreference>("gateway_wifi_password")?.apply {
            setSummaryProvider {
                val length = (it as? EditTextPreference)?.text?.length ?: 0

                if (length > 0) "*".repeat(length) else "Not set"
            }
        }
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
        findPreference<Preference>("location_permissions")?.apply {
            setOnPreferenceClickListener {
                mWifiClientHandler?.apply {
                    if (!hasLocationPermissions()) {
                        requestLocationPermissions {
                            updateSettingsState(context)
                        }
                    }
                    else if (!hasBackgroundLocationPermission()) {
                        requestBackgroundLocationPermissions {
                            updateSettingsState(context)
                        }
                    }
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
                                "This would require your device to be rooted."
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

        findPreference<Preference>("location_permissions")?.apply {
            val hasLocationPermissions = mWifiClientHandler?.hasLocationPermissions() ?: false
            val hasBackgroundLocationPermissions = mWifiClientHandler?.hasBackgroundLocationPermission() ?: false
            isEnabled = !(hasLocationPermissions && hasBackgroundLocationPermissions)
            summary = if (!hasLocationPermissions)
                "Precise location permission is required to get connected wifi information"
            else if (!hasBackgroundLocationPermissions)
                "Please also grant background location permission by selecting \"Allow all the time\""
            else
                "Already granted"
            icon = if (isEnabled) mErrorIcon else if (hasLocationPermissions && hasBackgroundLocationPermissions) mDoneIcon else null
        }

        val manageUSBPermissionGranted = context.checkSelfPermission("android.permission.MANAGE_USB") == PackageManager.PERMISSION_GRANTED
        findPreference<Preference>("manage_usb_permission")?.apply {
            isEnabled = !manageUSBPermissionGranted
            summary = if (!manageUSBPermissionGranted) "Required for fallback to USB Android Auto in gateway mode" else "Already granted"
            icon = if (manageUSBPermissionGranted) mDoneIcon else null
        }
        findPreference<Preference>("usb_fallback")?.apply {
            isEnabled = manageUSBPermissionGranted
            summary = if (!manageUSBPermissionGranted) "The app doesn't have the required Manage USB permission" else "When wireless connection fails, start USB Android Auto in this device"
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

    private fun setComponentEnabledForPreference(preference: Preference, enabled: Boolean) {
        val componentName = when (preference.key) {
            "is_wireless_client" -> {
                ComponentName(preference.context, BluetoothReceiver::class.java)
            }
            "is_gateway" -> {
                ComponentName(preference.context, USBReceiverActivity::class.java)
            }
            else -> {
                null
            }
        }

        componentName?.let {
            preference.context.packageManager.setComponentEnabledSetting(
                it,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}