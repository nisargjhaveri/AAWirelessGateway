package com.nisargjhaveri.aagateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {
    private var mBluetoothHandler: BluetoothHandler? = null

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
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key.equals("gateway_bt_mac") || preference.key.equals("client_bt_mac")) {
            setBluetoothDevices(preference)
        }

        super.onDisplayPreferenceDialog(preference)
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