package com.nisargjhaveri.aagateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
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

        if (mRunning) return START_STICKY

        updateNotification("Started")

        mAccessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) as UsbAccessory?
        if (mAccessory == null) {
            stopService("No USB accessory found")
            return START_STICKY
        }

        mUSBFileDescriptor = mUsbManager.openAccessory(mAccessory)
        if (mUSBFileDescriptor != null) {
            val fd = mUSBFileDescriptor?.fileDescriptor
            mPhoneInputStream = FileInputStream(fd)
            mPhoneOutputStream = FileOutputStream(fd)
        } else {
            stopService("Cannot open USB accessory")
            return START_STICKY
        }

        //Manually start AA.
        mRunning = true
        mUsbComplete = false
        mLocalComplete = false
        mHotspotStarted = false

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val clientAddress = preferences.getString("client_bt_mac", null)

        clientAddress?.also { address ->
            updateNotification("Starting wifi hotspot")
            mWifiHotspotHandler.start { success ->
                if (success) {
                    mHotspotStarted = true

                    updateNotification("Waiting for wireless client")
                    mBluetoothHandler.connectDevice(address) { msg ->
                        Log.d(LOG_TAG, msg)
                    }

                    USBPollThread().start()
                    TCPPollThread().start()
                }
                else {
                    stopService("Could not start wifi hotspot")
                }
            }
        }

        return START_STICKY
    }

    fun stopService(msg: String) {
        updateNotification(msg)

        if (mHotspotStarted) {
            mWifiHotspotHandler.stop()
            mHotspotStarted = false
        }

        stopForeground(false)
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        mRunning = false
    }

    private inner class USBPollThread: Thread() {
        override fun run() {
            super.run()

            val buffer = ByteArray(16384)

            if (mPhoneInputStream == null || mPhoneOutputStream == null) {
                mRunning = false
                stopService("Error initializing USB")
                return
            }

            val phoneInputStream = mPhoneInputStream!!

            if (mRunning) {
                mUsbComplete = true
            }

            if (!mLocalComplete && mRunning) {
                updateNotification("Waiting for TCP")
            }

            while (!mLocalComplete && mRunning) {
                try {
                    sleep(100)
                } catch (e: InterruptedException) {
                    // Log.e(TAG, "usb - error sleeping "+e.getMessage());
                }
            }

            while (mRunning)
            {
                try {
                    val len = phoneInputStream.read(buffer)
                    mSocketOutputStream?.write(buffer.copyOf(len))

                    if (mLogCommunication) Log.v(LOG_TAG, "USB read: ${buffer.toHex().substring(0, len*2)}")
                }
                catch (e: Exception)
                {
                    // Log.e(TAG,"usb - in main loop " + e.getMessage());
                    mRunning = false
                    stopService("Error in USB main loop")
                }
            }

            mUSBFileDescriptor?.also {
                try {
                    it.close()
                } catch (e: IOException) {
                    // Log.d(TAG, "error closing usb " + e.getMessage());
                }
            }
            // Log.d(TAG,"usb - end");
            stopService("USB main loop stopped")
        }

    }

    private inner class TCPPollThread: Thread() {
        override fun run() {
            super.run()

            var socket: Socket? = null

            try {
                val serverSocket = ServerSocket(5288, 5)
                serverSocket.soTimeout = 60000
                serverSocket.reuseAddress = true

                socket = serverSocket.accept()
                socket.soTimeout = 10000

                try {
                    serverSocket.close()
                }
                catch (e: IOException) {
                    // Ignore
                }

                mSocketOutputStream = socket?.getOutputStream()
                mSocketInputStream = DataInputStream(socket?.getInputStream())

                updateNotification("Connected!")
            }
            catch (e: SocketTimeoutException) {
                mRunning = false
                stopService("Wireless client did not connect")
            }
            catch (e: IOException) {
                e.printStackTrace()
                mRunning = false
                stopService("Error initializing TCP")
            }

            if (mRunning) {
                mLocalComplete = true
            }

            if (!mUsbComplete && mRunning) {
                updateNotification("Waiting for USB")
            }

            while (!mUsbComplete && mRunning) {
                try {
                    sleep(10)
                } catch (e: InterruptedException) {
                    //Log.e(TAG, "tcp - error sleeping " + e.message)
                }
            }

            val buffer = ByteArray(16384)
            while (mRunning) {
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
                    //Log.e(TAG, "tcp - in main loop " + e.message)
                    mRunning = false
                    stopService("Error in TCP main loop")
                }
            }

            if (socket != null) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            stopService("TCP main loop stopped")
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