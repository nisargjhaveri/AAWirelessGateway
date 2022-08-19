package com.nisargjhaveri.aagateway

import WifiInfoRequestOuterClass
import WifiStartRequestOuterClass
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

class AABluetoothProfileHandler (context: Context) {
    companion object {
        private const val LOG_TAG = "AAService"

        private val A2DP_SOURCE_UUID = UUID.fromString("00001112-0000-1000-8000-00805F9B34FB")

//        val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

        private val AA_LISTENER_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    }

    private val mContext = context

    private var mBluetoothAdapter: BluetoothAdapter? = null

    private lateinit var mWifiHotspotInfo: WifiHotspotInfo
    private lateinit var mCallback: (Boolean) -> Unit

    private var mConnectThread: AAConnectThread? = null

    init {
        val bluetoothManager: BluetoothManager =
            mContext.getSystemService(BluetoothManager::class.java)
        mBluetoothAdapter = bluetoothManager.adapter
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, "AA Bluetooth: $message")
    }

    fun connectDevice(mac: String, timeout: Long, wifiHotspotInfo: WifiHotspotInfo, callback: (Boolean) -> Unit) {
        mWifiHotspotInfo = wifiHotspotInfo
        mCallback = callback

        mBluetoothAdapter?.let { adapter ->
            val device = adapter.getRemoteDevice(mac.uppercase())

            AAProfileListenerThread().start()
            mConnectThread = AAConnectThread(device, timeout).apply {
                start()
            }
        }
    }

    fun cleanup() {
        mConnectThread?.cancel()
        mConnectThread = null
    }

    fun getWifiStartRequest(): WifiStartRequestOuterClass.WifiStartRequest {
        val wifiStartRequestBuilder = WifiStartRequestOuterClass.WifiStartRequest.newBuilder()
        wifiStartRequestBuilder.ipAddress = mWifiHotspotInfo.ipAddress
        wifiStartRequestBuilder.port = 5288

        return wifiStartRequestBuilder.build()
    }

    fun getWifiInfoRequest(): WifiInfoRequestOuterClass.WifiInfoRequest {
        val wifiInfoRequestBuilder = WifiInfoRequestOuterClass.WifiInfoRequest.newBuilder()
        wifiInfoRequestBuilder.ssid = mWifiHotspotInfo.ssid
        wifiInfoRequestBuilder.key = mWifiHotspotInfo.passphrase
        wifiInfoRequestBuilder.bssid = mWifiHotspotInfo.bssid
        wifiInfoRequestBuilder.securityMode = mWifiHotspotInfo.securityMode
        wifiInfoRequestBuilder.accessPointType = mWifiHotspotInfo.accessPointType

        return wifiInfoRequestBuilder.build()
    }

    fun send(socket: BluetoothSocket, byteArray: ByteArray, s: Short) {
        val sendBuffer = ByteBuffer.allocate(byteArray.size + 4)
        sendBuffer.putShort(byteArray.size.toShort())
        sendBuffer.putShort(s)
        sendBuffer.put(byteArray)

        socket.outputStream.write(sendBuffer.array())
    }

    private inner class AAConnectThread(device: BluetoothDevice, timeout: Long) : Thread() {
        var mmSocket: BluetoothSocket? = null
        var mDevice = device
        var mTimeout = timeout

        var mRunning = true

        override fun run() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && mContext.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            mmSocket = mDevice.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)

            mmSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                var connected = false

                val delay: Long = 500

                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    mRunning = false;
                }, mTimeout)

                while (!connected && mRunning) {
                    try {
                        socket.connect()
                        connected = true
                        log("BT Connection successful")
                    } catch (e: IOException) {
                        log("BT Connection failed: ${e.message}")
                        sleep(delay)
                    }
                }

                if (!connected) {
                    log("BT Connection failed")
                }

                mCallback.invoke(connected)
            }
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

    private inner class AAProfileListenerThread() : Thread() {
        private var mServerSocket: BluetoothServerSocket? = null
        private var mSocket: BluetoothSocket? = null

        override fun run() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && mContext.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            log("Creating service record for AA Listener")
            mServerSocket = mBluetoothAdapter?.listenUsingRfcommWithServiceRecord("AA Listener", AA_LISTENER_UUID)

//            log("Creating service record for HFP")
//            val hfpServerSocket = mBluetoothAdapter?.listenUsingRfcommWithServiceRecord("HFP", HFP_UUID)

            mSocket = mServerSocket?.accept()
            log("Got connection on AA Listener")

            try {
                mSocket?.let { socket ->
                    send(socket, getWifiStartRequest().toByteArray(), 1)
                    log("Sent WifiStartRequest")

                    val byteArray = ByteArray(1024)
                    socket.inputStream.read(byteArray)

                    val readBuffer = ByteBuffer.wrap(byteArray)
                    val length = readBuffer.short
                    val s = readBuffer.short
                    log("Read WifiStartRequest response. length: $length, s: $s")

                    if (s == 2.toShort()) {
                        send(socket, getWifiInfoRequest().toByteArray(), 3)
                        log("Sent WifiInfoRequest")
                    }
                }
            }
            catch (e: java.lang.Exception) {
                e.printStackTrace()
            }

            mSocket?.close()
            mSocket = null

            mServerSocket?.close()
//            hfpServerSocket?.close()
        }
    }
}
