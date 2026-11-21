package com.nisargjhaveri.aagateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.*
import java.net.*


class AAGatewayService : Service() {
    companion object {
        private const val LOG_TAG = "AAService"

        private const val NOTIFICATION_CHANNEL_ID = "default"
        private const val NOTIFICATION_ID = 1

        private const val DEFAULT_HANDSHAKE_TIMEOUT = 15
        private const val DEFAULT_CONNECTION_TIMEOUT = 60

        const val AA_PORT = 5288
        private const val CONTROL_PORT = 5287

        // Android Auto frame header: [channel][flags][len_hi][len_lo], followed by a
        // 4-byte total length when this is the first fragment of a multi-fragment message.
        private const val FRAME_FLAG_FIRST = 0x01
        private const val FRAME_FLAG_LAST = 0x02
        private const val MAX_HEADER_SIZE = 8
        private const val MAX_PAYLOAD_SIZE = 0xFFFF
        private const val USB_BUFFER_SIZE = 16384
    }

    private var mLogCommunication = false

    // These flags and streams are shared between the main thread and the USB/TCP worker
    // threads, so they must be volatile to make writes visible across threads.
    @Volatile private var mRunning = false

    private var mAccessory: UsbAccessory? = null

    @Volatile private var mPhoneInputStream: FileInputStream? = null
    @Volatile private var mPhoneOutputStream: FileOutputStream? = null

    @Volatile private var mSocketInputStream: DataInputStream? = null
    @Volatile private var mSocketOutputStream: OutputStream? = null

    @Volatile private var mUsbComplete = false
    @Volatile private var mLocalComplete = false
    private var mHotspotStarted = false
    @Volatile private var mHotspotIpAddress: String? = null

    private var mNativeConnectionFlow = true
    private var mUsbFallback = false
    private var mClientHandshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT
    private var mClientConnectionTimeout = DEFAULT_CONNECTION_TIMEOUT

    private val mMainHandlerThread = MainHandlerThread()

    private val mUsbManager: UsbManager by lazy { getSystemService(UsbManager::class.java) }

    private val mWifiHotspotHandler: WifiHotspotHandler by lazy { WifiHotspotHandler(this) }
    private val mBluetoothHandler: BluetoothHandler by lazy { BluetoothHandler(this, null) }
    private val mAABluetoothProfileHandler: AABluetoothProfileHandler by lazy { AABluetoothProfileHandler(this) }

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "General", NotificationManager.IMPORTANCE_DEFAULT)
        notificationChannel.setSound(null, null)
        notificationChannel.enableVibration(false)
        notificationChannel.enableLights(false)
        notificationManager.createNotificationChannel(notificationChannel)
    }

    private fun updateNotification(msg: String) {
        Log.i(LOG_TAG, "Notification updated: $msg")
        val notificationBuilder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(msg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Never redeliver: after a process restart the USB accessory in the old intent is
        // most likely gone, and the next USB_ACCESSORY_ATTACHED starts us again anyway.
        if (isRunning()) return START_NOT_STICKY

        updateNotification("Started")

        mAccessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as UsbAccessory?
        if (mAccessory == null) {
            Log.e(LOG_TAG, "No USB accessory found")
            stopService()
            return START_NOT_STICKY
        }

        //Manually start AA.
        mRunning = true
        mUsbComplete = false
        mLocalComplete = false
        mHotspotStarted = false

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val clientAddress = preferences.getString("client_bt_mac", null)

        mNativeConnectionFlow = preferences.getBoolean("native_connection_flow", true)
        mUsbFallback = preferences.getBoolean("usb_fallback", false)
        mClientHandshakeTimeout = preferences.getInt("client_handshake_timeout", DEFAULT_HANDSHAKE_TIMEOUT)
        mClientConnectionTimeout = preferences.getInt("client_connection_timeout", DEFAULT_CONNECTION_TIMEOUT)

        val hotspotSsid = preferences.getString("hotspot_ssid", null)?.trim() ?: ""
        val hotspotPassphrase = preferences.getString("hotspot_password", null) ?: ""
        val hotspotBssid = preferences.getString("hotspot_bssid", null)?.trim()?.ifEmpty { null }

        val address = clientAddress?.ifEmpty { null }
        if (address == null) {
            // Previously the service stayed in the foreground forever in this case.
            failStart("No client Bluetooth device configured")
            return START_NOT_STICKY
        }

        if (mNativeConnectionFlow && (hotspotSsid.isEmpty() || hotspotPassphrase.isEmpty())) {
            failStart("Hotspot SSID/password not configured")
            return START_NOT_STICKY
        }

        run {
            updateNotification("Starting wifi hotspot")
            mWifiHotspotHandler.start(hotspotSsid, hotspotPassphrase, hotspotBssid) { wifiSuccess, wifiHotspotInfo ->
                if (!isRunning()) {
                    // Service was stopped while the hotspot was coming up
                    Log.i(LOG_TAG, "Hotspot callback after stop, ignoring")
                    mHotspotStarted = wifiSuccess
                    stopService()
                    return@start
                }

                if (wifiSuccess && wifiHotspotInfo != null) {
                    mHotspotStarted = true
                    mHotspotIpAddress = wifiHotspotInfo.ipAddress

                    updateNotification("Waiting for wireless client")

                    if (mNativeConnectionFlow) {
                        // Callback is delivered on the main thread
                        mAABluetoothProfileHandler.connectDevice(address, mClientHandshakeTimeout * 1000L, wifiHotspotInfo) { success ->
                            onInitialHandshake(success, 0)
                        }
                    }
                    else {
                        mBluetoothHandler.connectDevice(address) { msg ->
                            Log.d(LOG_TAG, "Bluetooth: $msg")
                        }
                    }

                    mMainHandlerThread.start()
                }
                else {
                    Log.e(LOG_TAG, "Could not start wifi hotspot")
                    mRunning = false
                    onMainHandlerThreadStopped()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun failStart(msg: String) {
        Log.e(LOG_TAG, msg)
        mRunning = false
        // Keep the reason visible to the user, but leave the foreground state
        updateNotification(msg)
        stopForeground(false)
        stopSelf()
    }

    private fun onInitialHandshake(success: Boolean, rejectReason: Int) {
        if (success && rejectReason == 0) {
            updateNotification("Initial Handshake done")
        }
        else {
            if (!mLocalComplete) {
                stopRunning("Handshake failed or rejected")
            }
            else {
                Log.i(LOG_TAG, "Handshake failed or rejected, but tcp is already connected. Ignoring.")
            }

            if (success) {
                Log.i(LOG_TAG, "Connection was rejected with reason $rejectReason")
            }
        }
    }

    private fun onLocalComplete() {
        // Wireless client found us over TCP, the Bluetooth handshake is not needed anymore
        mAABluetoothProfileHandler.cleanup()
    }

    private fun onMainHandlerThreadStopped() {
        mAABluetoothProfileHandler.stop()

        if (!mLocalComplete && mUsbFallback) {
            mAccessory?.let {
                updateNotification("Starting USB Android Auto")
                if (!AndroidAutoHelper.startUsbAndroidAuto(this@AAGatewayService, it)) {
                    Log.i(LOG_TAG, "Failed starting USB Android Auto")
                }
            }
        }

        stopService()
    }

    private fun stopHotspot() {
        if (mHotspotStarted) {
            Log.i(LOG_TAG, "Stopping wifi hotspot")
            mWifiHotspotHandler.stop()
            mHotspotStarted = false
        }
    }

    private fun stopService() {
        stopHotspot()

        stopForeground(true)
        stopSelf()
    }

    private fun stopRunning(msg: String, notify: Boolean = true) {
        Log.i(LOG_TAG, msg)

        if (mRunning) {
            mRunning = false
            if (notify) updateNotification("Stopping wireless connection")
        }

        mMainHandlerThread.cancel()
    }

    private fun isRunning() = mRunning

    // Not a bound service. TODO() here would crash the app if anything tried to bind.
    override fun onBind(p0: Intent?): IBinder? = null

    override fun onDestroy() {
        // Must not call startForeground() from onDestroy, so don't update the notification
        stopRunning("Service onDestroy", notify = false)
        mAABluetoothProfileHandler.stop()
        stopHotspot()
        super.onDestroy()
    }

    private inner class MainHandlerThread: Thread() {
        private val mUsbThread = USBPollThread()
        private val mTcpThread = TCPPollThread()

        private val mTcpControlThread = TCPControlThread()

        fun cancel() {
            mUsbThread.cancel()
            mTcpThread.cancel()
            mTcpControlThread.cancel()
        }

        override fun run() {
            super.run()

            mUsbThread.start()
            mTcpThread.start()

            if (!mNativeConnectionFlow) {
                mTcpControlThread.start()
            }

            mUsbThread.join()
            mTcpThread.join()

            Handler(mainLooper).post {
                onMainHandlerThreadStopped()
            }
        }
    }

    private inner class USBPollThread: Thread() {
        var mUsbFileDescriptor: ParcelFileDescriptor? = null

        fun cancel() {
            mUsbFileDescriptor?.runCatching {
                close()
            }
        }

        override fun run() {
            super.run()

            if (isRunning() && !mLocalComplete) {
                updateNotification("Waiting for TCP")
            }

            while (isRunning() && !mLocalComplete) {
                try {
                    sleep(100)
                } catch (e: InterruptedException) {
                    Log.e(LOG_TAG, "usb - error sleeping: ${e.message}")
                }
            }

            if (isRunning()) {
                mUsbFileDescriptor = mUsbManager.openAccessory(mAccessory)?.also {
                    val fd = it.fileDescriptor
                    mPhoneInputStream = FileInputStream(fd)
                    mPhoneOutputStream = FileOutputStream(fd)
                }

                if (mUsbFileDescriptor == null || mPhoneInputStream == null || mPhoneOutputStream == null) {
                    stopRunning("Error initializing USB")
                }
            }

            if (isRunning()) {
                mUsbComplete = true
            }

            if (isRunning()) {
                val phoneInputStream = mPhoneInputStream!!

                val buffer = ByteArray(USB_BUFFER_SIZE)
                while (isRunning()) {
                    try {
                        val len: Int
                        try {
                            len = phoneInputStream.read(buffer)
                            if (len < 0) {
                                // EOF: headunit closed the accessory (e.g. unplugged)
                                throw EOFException("USB accessory closed")
                            }
                            if (len == 0) continue
                            if (mLogCommunication) Log.v(LOG_TAG, "USB read: ${buffer.copyOf(len).toHex()}")
                        }
                        catch (e: Exception) {
                            Log.e(LOG_TAG, "usb main loop - usb read error: ${e.message}")
                            throw e
                        }

                        try {
                            mSocketOutputStream?.write(buffer, 0, len)
                        }
                        catch (e: Exception) {
                            Log.e(LOG_TAG, "usb main loop - tcp write error: ${e.message}")
                            throw e
                        }
                    }
                    catch (e: Exception) {
                        stopRunning("Error in USB main loop")
                    }
                }
            }

            mUsbFileDescriptor?.apply {
                try {
                    close()
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "usb - error closing file descriptor: ${e.message}")
                }
            }

            stopRunning("USB main loop stopped")
        }

    }

    private inner class TCPPollThread: Thread() {
        var mServerSocket: ServerSocket? = null
        var mSocket: Socket? = null

        fun cancel() {
            mServerSocket?.runCatching {
                close()
            }
            mServerSocket = null

            mSocket?.runCatching {
                close()
            }
            mSocket = null
        }

        override fun run() {
            super.run()

            try {
                mServerSocket = createServerSocket(AA_PORT).apply {
                    soTimeout = mClientConnectionTimeout * 1000
                }

                mServerSocket?.let {
                    mSocket = it.accept().apply {
                        soTimeout = 10000
                        // AA carries many small, latency sensitive messages (touch input,
                        // audio/video acks). Don't let Nagle's algorithm batch them up.
                        tcpNoDelay = true
                    }
                }

                mServerSocket?.runCatching {
                    close()
                }
                mServerSocket = null

                mSocket?.also {
                    mSocketOutputStream = it.getOutputStream()
                    mSocketInputStream = DataInputStream(it.getInputStream())
                }

                Log.i(LOG_TAG, "TCP connected")
            }
            catch (e: SocketTimeoutException) {
                stopRunning("Wireless client did not connect")
            }
            catch (e: IOException) {
                Log.e(LOG_TAG, "tcp - error initializing: ${e.message}")
                stopRunning("Error initializing TCP")
            }

            if (isRunning() && mSocket == null) {
                stopRunning("Error connecting to wireless client")
            }

            if (isRunning()) {
                mLocalComplete = true
                onLocalComplete()
            }

            if (isRunning() && !mUsbComplete) {
                updateNotification("Waiting for USB")
            }

            while (isRunning() && !mUsbComplete) {
                try {
                    sleep(10)
                } catch (e: InterruptedException) {
                    Log.e(LOG_TAG, "tcp - error sleeping ${e.message}")
                }
            }

            if (isRunning()) {
                updateNotification("Connected!")
            }

            // The frame length field is 16 bit, so a frame can be up to 8 + 65535 bytes.
            // The previous 16 KiB buffer overflowed (IndexOutOfBounds -> disconnect) on
            // large encrypted fragments.
            val buffer = ByteArray(MAX_HEADER_SIZE + MAX_PAYLOAD_SIZE)
            while (isRunning()) {
                try {
                    var pos = 4
                    val encLen: Int
                    try {
                        val socketInputStream = mSocketInputStream ?: throw IOException("TCP not connected")

                        socketInputStream.readFully(buffer, 0, 4)

                        // The header carries an extra 4-byte total length only for the first
                        // fragment of a fragmented message (FIRST set, LAST not set). The old
                        // check (flags == 9) missed e.g. control (0x0D) or unencrypted (0x01)
                        // first fragments and desynchronised the stream.
                        val flags = buffer[1].toInt() and 0xFF
                        if ((flags and (FRAME_FLAG_FIRST or FRAME_FLAG_LAST)) == FRAME_FLAG_FIRST) {
                            pos += 4
                            socketInputStream.readFully(buffer, 4, 4)
                        }

                        encLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)

                        socketInputStream.readFully(buffer, pos, encLen)

                        if (mLogCommunication) Log.v(LOG_TAG, "TCP read: ${buffer.copyOf(encLen + pos).toHex()}")
                    }
                    catch (e: Exception) {
                        Log.e(LOG_TAG, "tcp main loop - tcp read error: ${e.message}")
                        throw e
                    }

                    try {
                        // Write the whole frame in a single USB transfer
                        mPhoneOutputStream?.write(buffer, 0, encLen + pos)
                    }
                    catch (e: Exception) {
                        Log.e(LOG_TAG, "tcp main loop - usb write error: ${e.message}")
                        throw e
                    }
                } catch (e: Exception) {
                    stopRunning("Error in TCP main loop")
                }
            }

            mSocket?.apply {
                try {
                    close()
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "tcp - error closing socket: ${e.message}")
                }
            }

            stopRunning("TCP main loop stopped")
        }
    }

    private inner class TCPControlThread: Thread() {
        var mServerSocket: ServerSocket? = null

        fun cancel() {
            mServerSocket?.runCatching {
                close()
            }
            mServerSocket = null
        }

        override fun run() {
            super.run()

            var success = false
            var readValue = -1

            try {
                mServerSocket = createServerSocket(CONTROL_PORT).apply {
                    soTimeout = mClientHandshakeTimeout * 1000
                }

                mServerSocket?.let {
                    it.accept().apply {
                        soTimeout = 10000

                        success = true
                        readValue = getInputStream().read()

                        close()
                    }
                }

                mServerSocket?.runCatching {
                    close()
                }
                mServerSocket = null
            }
            catch (e: SocketTimeoutException) {
                Log.e(LOG_TAG, "Initial handshake did not happen in time")
            }
            catch (e: IOException) {
                Log.e(LOG_TAG, "tcp handshake - error initializing: ${e.message}")
            }

            Handler(mainLooper).post {
                onInitialHandshake(success, readValue)
            }
        }
    }

    /**
     * Listen only on the hotspot interface when we know its address, so the AA stream is
     * not exposed on other networks (e.g. mobile data). Fall back to all interfaces if
     * binding fails (address detection on some devices is unreliable).
     */
    private fun createServerSocket(port: Int): ServerSocket {
        mHotspotIpAddress?.let { ip ->
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(ip), port), 5)
                }
            }
            catch (e: IOException) {
                Log.w(LOG_TAG, "Could not bind port $port to $ip, listening on all interfaces: ${e.message}")
            }
        }

        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), 5)
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