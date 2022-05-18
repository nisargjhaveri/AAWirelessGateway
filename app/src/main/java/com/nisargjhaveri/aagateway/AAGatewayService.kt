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

        private const val DEFAULT_HANDSHAKE_TIMEOUT = 60
        private const val DEFAULT_CONNECTION_TIMEOUT = 180
    }

    private var mLogCommunication = false

    private var mRunning = false

    private var mAccessory: UsbAccessory? = null
    private var mUSBFileDescriptor: ParcelFileDescriptor? = null

    private var mPhoneInputStream: FileInputStream? = null
    private var mPhoneOutputStream: FileOutputStream? = null

    private var mSocketInputStream: DataInputStream? = null
    private var mSocketOutputStream: OutputStream? = null

    private var mUsbComplete = false
    private var mLocalComplete = false
    private var mHotspotStarted = false

    private var mUsbFallback = false
    private var mClientHandshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT
    private var mClientConnectionTimeout = DEFAULT_CONNECTION_TIMEOUT

    private val mMainHandlerThread = MainHandlerThread()

    private val mUsbManager: UsbManager by lazy { getSystemService(UsbManager::class.java) }

    private val mWifiHotspotHandler: WifiHotspotHandler by lazy { WifiHotspotHandler(this) }
    private val mBluetoothHandler: BluetoothHandler by lazy { BluetoothHandler(this, null) }

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

        if (isRunning()) return START_REDELIVER_INTENT

        updateNotification("Started")

        mAccessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as UsbAccessory?
        if (mAccessory == null) {
            Log.e(LOG_TAG, "No USB accessory found")
            stopService()
            return START_REDELIVER_INTENT
        }

        mUSBFileDescriptor = mUsbManager.openAccessory(mAccessory)
        if (mUSBFileDescriptor != null) {
            val fd = mUSBFileDescriptor?.fileDescriptor
            mPhoneInputStream = FileInputStream(fd)
            mPhoneOutputStream = FileOutputStream(fd)
        } else {
            Log.e(LOG_TAG, "Cannot open USB accessory")
            stopService()
            return START_REDELIVER_INTENT
        }

        //Manually start AA.
        mRunning = true
        mUsbComplete = false
        mLocalComplete = false
        mHotspotStarted = false

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val clientAddress = preferences.getString("client_bt_mac", null)

        mUsbFallback = preferences.getBoolean("usb_fallback", false)
        mClientHandshakeTimeout = if (mUsbFallback) { preferences.getInt("client_handshake_timeout", 15) } else { DEFAULT_HANDSHAKE_TIMEOUT }
        mClientConnectionTimeout = if (mUsbFallback) { preferences.getInt("client_connection_timeout", 60) } else { DEFAULT_CONNECTION_TIMEOUT }

        clientAddress?.also { address ->
            updateNotification("Starting wifi hotspot")
            mWifiHotspotHandler.start { wifiSuccess ->
                if (wifiSuccess) {
                    mHotspotStarted = true

                    updateNotification("Waiting for wireless client")
                    mBluetoothHandler.connectDevice(address) { msg ->
                        Log.d(LOG_TAG, msg)
                    }

                    mMainHandlerThread.start()
                }
                else {
                    Log.e(LOG_TAG, "Could not start wifi hotspot")
                    stopService()
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun onInitialHandshake(success: Boolean, rejectReason: Int) {
        if (success && rejectReason == 0) {
            updateNotification("Initial Handshake done")
        }
        else {
            mMainHandlerThread.cancel()

            if (success) {
                Log.d(LOG_TAG, "Connection was rejected with reason $rejectReason")
            }
        }
    }

    private fun onMainHandlerThreadStopped() {
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

    private fun stopService() {
        if (mHotspotStarted) {
            mWifiHotspotHandler.stop()
            mHotspotStarted = false
        }

        stopForeground(true)
        stopSelf()
    }

    private fun stopRunning(msg: String) {
        if (mRunning) {
            mRunning = false
            updateNotification("Stopping wireless connection")
        }

        Log.i(LOG_TAG, msg)
    }

    private fun isRunning() = mRunning

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRunning("Service onDestroy")
    }

    private inner class MainHandlerThread: Thread() {
        private val mUsbThread = USBPollThread()
        private val mTcpThread = TCPPollThread()

        private val mTcpControlThread = TCPControlThread()

        fun cancel() {
            stopRunning("Cancelled")

            mUsbThread.cancel()
            mTcpThread.cancel()
            mTcpControlThread.cancel()
        }

        override fun run() {
            super.run()

            mUsbThread.start()
            mTcpThread.start()

            mTcpControlThread.start()

            mUsbThread.join()
            mTcpThread.join()

            Handler(mainLooper).post {
                onMainHandlerThreadStopped()
            }
        }
    }

    private inner class USBPollThread: Thread() {
        fun cancel() {
            // Don't do anything
        }

        override fun run() {
            super.run()

            if (mPhoneInputStream == null || mPhoneOutputStream == null) {
                stopRunning("Error initializing USB")
                return
            }

            val phoneInputStream = mPhoneInputStream!!

            if (isRunning()) {
                mUsbComplete = true
            }

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

            val buffer = ByteArray(16384)
            while (isRunning())
            {
                try {
                    val len = phoneInputStream.read(buffer)
                    mSocketOutputStream?.write(buffer.copyOf(len))

                    if (mLogCommunication) Log.v(LOG_TAG, "USB read: ${buffer.copyOf(len).toHex()}")
                }
                catch (e: Exception)
                {
                    Log.e(LOG_TAG,"usb - error in main loop: ${e.message}")
                    stopRunning("Error in USB main loop")
                }
            }

            mUSBFileDescriptor?.apply {
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

        fun cancel() {
            mServerSocket?.runCatching {
                close()
            }
        }

        override fun run() {
            super.run()

            var socket: Socket? = null

            try {
                mServerSocket = ServerSocket(5288, 5).apply {
                    soTimeout = mClientConnectionTimeout * 1000
                    reuseAddress = true
                }

                mServerSocket?.let {
                    socket = it.accept().apply {
                        soTimeout = 10000
                    }
                }

                mServerSocket?.runCatching {
                    close()
                }
                mServerSocket = null

                socket?.also {
                    mSocketOutputStream = it.getOutputStream()
                    mSocketInputStream = DataInputStream(it.getInputStream())
                }

                updateNotification("Connected!")
            }
            catch (e: SocketTimeoutException) {
                stopRunning("Wireless client did not connect")
            }
            catch (e: IOException) {
                Log.e(LOG_TAG, "tcp - error initializing: ${e.message}")
                stopRunning("Error initializing TCP")
            }

            if (isRunning() && socket == null) {
                stopRunning("Error connecting to wireless client")
            }

            if (isRunning()) {
                mLocalComplete = true
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

            val buffer = ByteArray(16384)
            while (isRunning()) {
                try {
                    var pos = 4

                    mSocketInputStream?.readFully(buffer, 0, 4)
                    if (buffer[1].toInt() == 9) //Flag 9 means the header is 8 bytes long (read four more bytes separately)
                    {
                        pos += 4
                        mSocketInputStream?.readFully(buffer, 4, 4)
                    }

                    val encLen: Int = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)

                    mSocketInputStream?.readFully(buffer, pos, encLen)
                    mPhoneOutputStream?.write(buffer.copyOf(encLen + pos))

                    if (mLogCommunication) Log.v(LOG_TAG, "TCP read: ${buffer.copyOf(encLen + pos).toHex()}")
                } catch (e: java.lang.Exception) {
                    Log.e(LOG_TAG, "tcp - error in main loop: ${e.message}")
                    stopRunning("Error in TCP main loop")
                }
            }

            socket?.apply {
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
        }

        override fun run() {
            super.run()

            var success = false
            var readValue = -1

            try {
                mServerSocket = ServerSocket(5287, 5).apply {
                    soTimeout = mClientHandshakeTimeout * 1000
                    reuseAddress = true
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