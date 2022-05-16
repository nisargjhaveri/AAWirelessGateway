package com.nisargjhaveri.aagateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.*

class BluetoothHandler (context: Context, activityResultCaller: ActivityResultCaller?) {
    data class BluetoothDeviceInfo(val address: String, val name: String?)

    private val mContext = context
    private val mActivityResultCaller = activityResultCaller

    private var mBluetoothAdapter: BluetoothAdapter? = null

    private var mEnableBluetoothCallback: ((Boolean) -> Unit)? = null
    private var mEnableBluetoothLauncher = mActivityResultCaller?.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val callback = mEnableBluetoothCallback
        mEnableBluetoothCallback = null

        if (it.resultCode == AppCompatActivity.RESULT_OK) {
            callback?.invoke(true)
        }
        else {
            callback?.invoke(false)
        }
    }

    private var mRequestPermissionsCallback: ((Boolean) -> Unit)? = null
    private val mRequestPermissionsLauncher = mActivityResultCaller?.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        val callback = mRequestPermissionsCallback
        mRequestPermissionsCallback = null

        if (isGranted) {
            callback?.invoke(true)
        }
        else {
            callback?.invoke(false)
        }
    }


    init {
        val bluetoothManager: BluetoothManager = mContext.getSystemService(BluetoothManager::class.java)
        mBluetoothAdapter = bluetoothManager.adapter
    }

    fun isSupported(): Boolean {
        return mBluetoothAdapter != null
    }

    fun isEnabled(): Boolean {
        return mBluetoothAdapter?.isEnabled ?: false
    }

    fun setEnabled(callback: ((success: Boolean) -> Unit)?) {
        mEnableBluetoothLauncher?.let {
            mEnableBluetoothCallback = callback
            it.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun hasConnectPermissions(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions(callback: ((success: Boolean) -> Unit)?) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return
        }

        mRequestPermissionsLauncher?.let {
            mRequestPermissionsCallback = callback
            it.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun connectDevice(mac: String, log: (String) -> Unit) {
        mBluetoothAdapter?.let { adapter ->
            val device = adapter.getRemoteDevice(mac.uppercase())
            ConnectThread(device, log).start()
        }
    }

    fun getBondedDevices(): List<BluetoothDeviceInfo> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && mContext.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return listOf()
        }

        mBluetoothAdapter?.let { adapter ->
            return adapter.bondedDevices.map {
                BluetoothDeviceInfo(it.address, it.name)
            }
        }

        return listOf()
    }

    private inner class ConnectThread(device: BluetoothDevice, val log: (String) -> Unit) : Thread() {
        var mmSocket: BluetoothSocket? = null
        var mDevice = device

        override fun run() {
            log("Inside connect thread")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && mContext.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            mmSocket = mDevice.createRfcommSocketToServiceRecord(UUID.fromString("2b12becb-c5c0-4370-b19c-0917c72e852c"))

            mmSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
//                var connected = false
//                while (!connected) {
                    try {
                        socket.connect()
//                        connected = true
                        log("BT Connection successful")
                    } catch (e: IOException) {
//                        sleep(100)
//                        log("Connection failed. Retrying")
                        log("BT Connection failed")
                    }
//                }
            }

//            log("Read: " + mmSocket?.inputStream?.read())
            mmSocket?.close()
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                log("Could not close the client socket")
            }
        }
    }

//    private inner class AcceptThread : Thread() {
//        private var mmServerSocket : BluetoothServerSocket? = null
//        private var mmSocket : BluetoothSocket? = null
//
//        override fun run() {
//            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && mContext.checkSelfPermission(
//                    Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                return
//            }
//
//            mmServerSocket = mBluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("BT", UUID.fromString("2b12becb-c5c0-4370-b19c-0917c72e852c"))
//
//            // Keep listening until exception occurs or a socket is returned.
//            var shouldLoop = true
//            while (shouldLoop) {
//                mmSocket = try {
//                    mmServerSocket?.accept()
//                } catch (e: IOException) {
//                    shouldLoop = false
//                    null
//                }
//                mmSocket?.also {
//                    mmServerSocket?.close()
//
//                    it.outputStream?.write(5)
//                    it.outputStream?.flush()
//                    shouldLoop = false
//                    it.close()
//                }
//            }
//        }
//
//        // Closes the connect socket and causes the thread to finish.
//        fun cancel() {
//            try {
//                mmServerSocket?.close()
//            } catch (e: IOException) {
////                Log.e(TAG, "Could not close the connect socket", e)
//            }
//        }
//    }
}