package com.nisargjhaveri.aagateway

import WifiInfoRequestOuterClass
import WifiStartRequestOuterClass
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AABluetoothProfileHandler (context: Context) {
    companion object {
        private const val LOG_TAG = "AAService"

        private val A2DP_SOURCE_UUID = UUID.fromString("00001112-0000-1000-8000-00805F9B34FB")

//        val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

        private val AA_LISTENER_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
    }

    private val mContext = context
    private val mMainHandler = Handler(Looper.getMainLooper())

    private var mBluetoothAdapter: BluetoothAdapter? = null

    private lateinit var mWifiHotspotInfo: WifiHotspotInfo
    private lateinit var mCallback: (Boolean) -> Unit
    private val mCallbackInvoked = AtomicBoolean(false)

    private var mConnectThread: AAConnectThread? = null
    private var mListenerThread: AAProfileListenerThread? = null

    init {
        val bluetoothManager: BluetoothManager =
            mContext.getSystemService(BluetoothManager::class.java)
        mBluetoothAdapter = bluetoothManager.adapter
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, "AA Bluetooth: $message")
    }

    private fun hasConnectPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
                || mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /** Invokes the result callback at most once, always on the main thread. */
    private fun notifyResult(success: Boolean) {
        if (mCallbackInvoked.compareAndSet(false, true)) {
            mMainHandler.post { mCallback.invoke(success) }
        }
    }

    fun connectDevice(mac: String, timeout: Long, wifiHotspotInfo: WifiHotspotInfo, callback: (Boolean) -> Unit) {
        mWifiHotspotInfo = wifiHotspotInfo
        mCallback = callback
        mCallbackInvoked.set(false)

        val adapter = mBluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth not available or not enabled")
            notifyResult(false)
            return
        }

        if (!hasConnectPermission()) {
            // Previously the threads silently returned and the gateway waited for the
            // full TCP timeout instead of failing (and falling back) right away.
            log("BLUETOOTH_CONNECT permission not granted")
            notifyResult(false)
            return
        }

        val device = try {
            adapter.getRemoteDevice(mac.uppercase())
        } catch (e: IllegalArgumentException) {
            log("Invalid Bluetooth address: $mac")
            notifyResult(false)
            return
        }

        mListenerThread = AAProfileListenerThread().apply { start() }
        mConnectThread = AAConnectThread(device, timeout).apply { start() }
    }

    /**
     * Called once the wireless client is connected over TCP: stop the connection attempts
     * and stop accepting new RFCOMM connections. An exchange that is still in progress
     * on the listener is allowed to finish.
     */
    fun cleanup() {
        mConnectThread?.cancel()
        mConnectThread = null

        mListenerThread?.stopListening()
    }

    /** Tear down everything, including a running RFCOMM exchange. */
    fun stop() {
        cleanup()

        mListenerThread?.cancel()
        mListenerThread = null
    }

    fun getWifiStartRequest(): WifiStartRequestOuterClass.WifiStartRequest {
        val wifiStartRequestBuilder = WifiStartRequestOuterClass.WifiStartRequest.newBuilder()
        wifiStartRequestBuilder.ipAddress = mWifiHotspotInfo.ipAddress
        wifiStartRequestBuilder.port = AAGatewayService.AA_PORT

        return wifiStartRequestBuilder.build()
    }

    // Note: despite the proto name, this is sent as the WifiInfoResponse (type 3)
    fun getWifiInfoRequest(): WifiInfoRequestOuterClass.WifiInfoRequest {
        val wifiInfoRequestBuilder = WifiInfoRequestOuterClass.WifiInfoRequest.newBuilder()
        wifiInfoRequestBuilder.ssid = mWifiHotspotInfo.ssid
        wifiInfoRequestBuilder.key = mWifiHotspotInfo.passphrase
        // bssid is a required proto field and the protobuf-lite setter throws on null
        wifiInfoRequestBuilder.bssid = mWifiHotspotInfo.bssid ?: "".also {
            log("Hotspot BSSID unknown, sending empty BSSID. Set \"Hotspot BSSID\" in settings.")
        }
        wifiInfoRequestBuilder.securityMode = mWifiHotspotInfo.securityMode
        wifiInfoRequestBuilder.accessPointType = mWifiHotspotInfo.accessPointType

        return wifiInfoRequestBuilder.build()
    }

    fun send(socket: BluetoothSocket, byteArray: ByteArray, s: Short) {
        val sendBuffer = ByteBuffer.allocate(byteArray.size + 4)
        sendBuffer.putShort(byteArray.size.toShort())
        sendBuffer.putShort(s)
        sendBuffer.put(byteArray)

        socket.outputStream.apply {
            write(sendBuffer.array())
            flush()
        }
    }

    /**
     * Reads one message: 2 bytes length, 2 bytes type, then the payload.
     * RFCOMM is a stream, a single read() may return a partial or multiple messages,
     * so read exactly the header and then exactly the payload.
     */
    fun read(socket: BluetoothSocket): Triple<Int, Short, ByteBuffer> {
        val input = DataInputStream(socket.inputStream)

        val length = input.readUnsignedShort()
        val s = input.readShort()

        val data = ByteArray(length)
        input.readFully(data)

        val name = when (s.toInt()) {
            1 -> "WifiStartRequest"
            2 -> "WifiInfoRequest"
            3 -> "WifiInfoResponse"
            4 -> "WifiVersionRequest"
            5 -> "WifiVersionResponse"
            6 -> "WifiConnectStatus"
            7 -> "WifiStartResponse"
            else -> "UNKNOWN"
        }

        log("Read $name. length: $length, s: $s, data: ${data.toHex()}")

        return Triple(length, s, ByteBuffer.wrap(data))
    }

    private inner class AAConnectThread(private val mDevice: BluetoothDevice, private val mTimeout: Long) : Thread() {
        @Volatile private var mmSocket: BluetoothSocket? = null
        @Volatile private var mRunning = true

        @SuppressLint("MissingPermission") // checked in connectDevice()
        override fun run() {
            val delay: Long = 500
            val deadline = SystemClock.elapsedRealtime() + mTimeout
            var connected = false

            while (!connected && mRunning && SystemClock.elapsedRealtime() < deadline) {
                // A BluetoothSocket cannot be reused after a failed connect(), it is closed.
                // The old code retried on the same socket, so only the first attempt was real.
                val socket = try {
                    mDevice.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                } catch (e: IOException) {
                    log("Could not create socket: ${e.message}")
                    break
                }
                mmSocket = socket

                try {
                    socket.connect()
                    connected = true
                    log("BT Connection successful")
                } catch (e: IOException) {
                    log("BT Connection failed: ${e.message}")
                    socket.runCatching { close() }
                    if (mRunning) {
                        try { sleep(delay) } catch (e: InterruptedException) { break }
                    }
                }
            }

            if (!mRunning) {
                log("BT Connection cancelled")
                return
            }

            if (!connected) {
                log("BT Connection failed")
            }

            notifyResult(connected)
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            mRunning = false
            interrupt()
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                log("Could not close the client socket")
            }
        }
    }

    private inner class AAProfileListenerThread : Thread() {
        @Volatile private var mServerSocket: BluetoothServerSocket? = null
        @Volatile private var mSocket: BluetoothSocket? = null

        fun stopListening() {
            mServerSocket?.runCatching { close() }
            mServerSocket = null
        }

        fun cancel() {
            stopListening()
            mSocket?.runCatching { close() }
            mSocket = null
        }

        @SuppressLint("MissingPermission") // checked in connectDevice()
        override fun run() {
            try {
                log("Creating service record for AA Listener")
                mServerSocket = mBluetoothAdapter?.listenUsingRfcommWithServiceRecord("AA Listener", AA_LISTENER_UUID)

                // Blocks until the phone connects, or until stopListening()/cancel() closes
                // the server socket. Previously this was never closed and leaked the thread
                // and the SDP record when the phone never connected.
                val socket = mServerSocket?.accept() ?: return
                mSocket = socket
                log("Got connection on AA Listener")

                // Only one connection is expected
                stopListening()

                send(socket, getWifiStartRequest().toByteArray(), 1)
                log("Sent WifiStartRequest")

                val (_, s, _) = read(socket)

                if (s == 2.toShort()) {
                    send(socket, getWifiInfoRequest().toByteArray(), 3)
                    log("Sent WifiInfoResponse")
                } else {
                    log("Expected WifiInfoRequest (s = 2), got s = $s")
                }

                // WifiStartResponse and WifiConnectStatus are expected
                read(socket)
                read(socket)
            }
            catch (e: IOException) {
                log("AA Listener stopped: ${e.message}")
            }
            catch (e: Exception) {
                Log.e(LOG_TAG, "AA Bluetooth: AA Listener error", e)
            }
            finally {
                cancel()
            }
        }
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()
    fun ByteArray.toHex(): String {
        val hexChars = CharArray(size * 2)

        var i = 0

        forEach {
            val octet = it.toInt()
            hexChars[i] = hexArray[(octet and 0xF0).ushr(4)]
            hexChars[i+1] = hexArray[(octet and 0x0F)]
            i += 2
        }

        return String(hexChars)
    }
}
