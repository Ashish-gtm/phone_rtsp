package com.phone_rtsp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal class StreamService : Service() {
    inner class LocalBinder : android.os.Binder() {
        val service: StreamService
            get() = this@StreamService
    }

    private val binder = LocalBinder()
    private lateinit var streamer: RtspCameraStreamer
    private var statusListener: ((Map<String, Any?>) -> Unit)? = null
    private var latestStatus: Map<String, Any?> = emptyMap()
    private var startedInThisProcess = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        latestStatus = readPersistedStatus(this)
        val previousState = latestStatus["state"] as? String
        if (previousState == "live" || previousState == "starting") {
            latestStatus = latestStatus.toMutableMap().apply {
                this["state"] = "stopped"
                this["message"] = "Streaming stopped because Android restarted the service."
            }
            persistStatus(latestStatus)
        }
        streamer = RtspCameraStreamer(applicationContext) { state, message ->
            onStreamerState(state, message)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    publishError("Camera permission is required to stream.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    enterForeground()
                    acquireLocks()
                    startedInThisProcess = true
                    latestStatus = currentStreamerStatus("starting", null)
                    persistStatus(latestStatus)
                    streamer.start(
                        intent.getBooleanExtra(EXTRA_FRONT_CAMERA, false),
                        intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                        intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                        intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH),
                        intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT),
                        intent.getIntExtra(EXTRA_FRAME_RATE, DEFAULT_FRAME_RATE),
                        intent.getIntExtra(EXTRA_BIT_RATE, DEFAULT_BIT_RATE),
                        intent.getBooleanExtra(EXTRA_ONVIF_ENABLED, false)
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to start camera streaming.", error)
                    if ((latestStatus["state"] as? String) != "error") {
                        publishError(error.message ?: "Unable to start camera streaming.")
                    }
                    releaseLocks()
                    finishForeground(keepErrorNotification = true)
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun setStatusListener(listener: ((Map<String, Any?>) -> Unit)?) {
        statusListener = listener
        listener?.invoke(currentStatus())
    }

    fun currentStatus(): Map<String, Any?> =
        if (startedInThisProcess) currentStreamerStatus() else latestStatus

    fun stopStreaming() {
        var stopError: Exception? = null
        try {
            if (startedInThisProcess) streamer.stop()
        } catch (error: Exception) {
            stopError = error
            Log.e(TAG, "Unable to stop camera streaming cleanly.", error)
        } finally {
            startedInThisProcess = false
            releaseLocks()
        }
        if (stopError != null) {
            publishError(stopError.message ?: "Unable to stop camera streaming cleanly.")
            finishForeground(keepErrorNotification = true)
            stopSelf()
            return
        }
        latestStatus = currentStreamerStatus("stopped", null)
        persistStatus(latestStatus)
        statusListener?.invoke(latestStatus)
        finishForeground(keepErrorNotification = false)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            if (startedInThisProcess) streamer.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Error stopping the RTSP streamer during service destruction.", error)
        } finally {
            startedInThisProcess = false
            releaseLocks()
            super.onDestroy()
        }
    }

    private fun onStreamerState(state: String, message: String?) {
        latestStatus = currentStreamerStatus(state, message)
        persistStatus(latestStatus)
        statusListener?.invoke(latestStatus)
        updateNotification(latestStatus)
        when (state) {
            "starting", "live" -> Unit
            "error" -> {
                startedInThisProcess = false
                releaseLocks()
                finishForeground(keepErrorNotification = true)
                stopSelf()
            }
            "stopped" -> {
                startedInThisProcess = false
                releaseLocks()
                finishForeground(keepErrorNotification = false)
                stopSelf()
            }
        }
    }

    private fun currentStreamerStatus(
        state: String? = null,
        message: String? = null
    ): Map<String, Any?> = streamer.status().toMutableMap().apply {
        if (state != null) this["state"] = state
        if (message != null || state != null) this["message"] = message
    }

    private fun publishError(message: String) {
        latestStatus = currentStreamerStatus("error", message)
        persistStatus(latestStatus)
        statusListener?.invoke(latestStatus)
        updateNotification(latestStatus)
    }

    private fun enterForeground() {
        val notification = buildNotification(currentStreamerStatus("starting", null))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:RtspCameraStream"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock?.isHeld != true) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "$packageName:RtspCameraStream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release the partial wake lock.", error)
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release the Wi-Fi lock.", error)
        }
        wifiLock = null
    }

    private fun finishForeground(keepErrorNotification: Boolean) {
        if (keepErrorNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun updateNotification(status: Map<String, Any?>) {
        val state = status["state"] as? String
        if (state == "stopped") return
        val notification = buildNotification(status)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was denied; continuing stream without updates.", error)
        }
    }

    private fun buildNotification(status: Map<String, Any?>): Notification {
        val state = status["state"] as? String ?: "starting"
        val address = status["address"] as? String
        val text = when (state) {
            "error" -> (status["message"] as? String) ?: "Streaming stopped because of an error."
            "live" -> "Streaming rtsp://${address ?: "phone"}:8554/live"
            else -> "Starting camera stream"
        }
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this,
            STOP_REQUEST,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Phone RTSP")
            .setContentText(text.take(MAX_NOTIFICATION_TEXT))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(state == "starting" || state == "live")
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status and controls for the RTSP camera stream."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun persistStatus(status: Map<String, Any?>) {
        val json = JSONObject()
        status.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(STATUS_KEY, json.toString())
            .apply()
    }

    companion object {
        const val ACTION_START = "com.phone_rtsp.action.START"
        const val ACTION_STOP = "com.phone_rtsp.action.STOP"
        const val EXTRA_FRONT_CAMERA = "frontCamera"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FRAME_RATE = "frameRate"
        const val EXTRA_BIT_RATE = "bitRate"
        const val EXTRA_ONVIF_ENABLED = "onvifEnabled"

        private const val TAG = "PhoneRtspStreamer"
        private const val CHANNEL_ID = "phone_rtsp_stream"
        private const val NOTIFICATION_ID = 8554
        private const val OPEN_APP_REQUEST = 8555
        private const val STOP_REQUEST = 8556
        private const val MAX_NOTIFICATION_TEXT = 300
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_FRAME_RATE = 25
        private const val DEFAULT_BIT_RATE = 4_000_000
        private const val PREFERENCES = "phone_rtsp_preferences"
        private const val STATUS_KEY = "stream_status"

        fun readPersistedStatus(context: Context): Map<String, Any?> {
            val fallback = mapOf(
                "state" to "stopped",
                "message" to null,
                "address" to null,
                "frontCamera" to false,
                "width" to DEFAULT_WIDTH,
                "height" to DEFAULT_HEIGHT,
                "frameRate" to DEFAULT_FRAME_RATE,
                "bitRate" to DEFAULT_BIT_RATE
            )
            val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(STATUS_KEY, null) ?: return fallback
            return try {
                val json = JSONObject(raw)
                fallback.mapValues { (key, default) ->
                    if (!json.has(key) || json.isNull(key)) default else json.get(key)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to read saved stream status.", error)
                fallback
            }
        }
    }
}
