package com.phone_rtsp

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var eventSink: EventChannel.EventSink? = null
    private var streamerService: StreamService? = null
    private var isBound = false
    private var pendingStart: MethodChannel.Result? = null
    private var pendingRequest: StartRequest? = null

    private data class StartRequest(
        val frontCamera: Boolean,
        val username: String,
        val password: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitRate: Int,
        val result: MethodChannel.Result
    )

    private val statusListener: (Map<String, Any?>) -> Unit = { status ->
        runOnUiThread { publishStatus(status) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? StreamService.LocalBinder ?: return
            streamerService = localBinder.service
            isBound = true
            streamerService?.setStatusListener(statusListener)
            streamerService?.let { publishStatus(it.currentStatus()) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamerService?.setStatusListener(null)
            streamerService = null
            isBound = false
            publishStatus(StreamService.readPersistedStatus(applicationContext))
        }
    }

    override fun onStart() {
        super.onStart()
        isBound = bindService(
            Intent(this, StreamService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        streamerService?.setStatusListener(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        streamerService = null
        super.onStop()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "phone_rtsp/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    eventSink = events
                    publishStatus(
                        streamerService?.currentStatus()
                            ?: StreamService.readPersistedStatus(applicationContext)
                    )
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "phone_rtsp/streamer")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getStatus" -> result.success(
                        streamerService?.currentStatus()
                            ?: StreamService.readPersistedStatus(applicationContext)
                    )
                    "start" -> requestCameraPermission(
                        StartRequest(
                            call.argument<Boolean>("frontCamera") ?: false,
                            call.argument<String>("username").orEmpty().trim(),
                            call.argument<String>("password").orEmpty(),
                            call.argument<Int>("width") ?: 1920,
                            call.argument<Int>("height") ?: 1080,
                            call.argument<Int>("frameRate") ?: 25,
                            call.argument<Int>("bitRate") ?: 4_000_000,
                            result
                        )
                    )
                    "stop" -> {
                        val stopIntent = Intent(this, StreamService::class.java)
                            .setAction(StreamService.ACTION_STOP)
                        try {
                            if (streamerService != null) {
                                streamerService?.stopStreaming()
                            } else {
                                startService(stopIntent)
                            }
                            result.success(null)
                        } catch (error: Exception) {
                            result.error("STOP_FAILED", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun requestCameraPermission(request: StartRequest) {
        if (request.username.isEmpty() != request.password.isEmpty()) {
            request.result.error(
                "INVALID_CREDENTIALS",
                "Enter both a username and password, or leave both blank.",
                null
            )
            return
        }
        if (pendingStart != null) {
            request.result.error("BUSY", "A camera permission request is already pending.", null)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission(request)
            return
        }
        pendingStart = request.result
        pendingRequest = request
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    private fun requestNotificationPermission(request: StartRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(NOTIFICATION_PERMISSION_ASKED, false)
        ) {
            getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(NOTIFICATION_PERMISSION_ASKED, true)
                .apply()
            pendingStart = request.result
            pendingRequest = request
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }
        clearPendingStart()
        startStream(request)
    }

    private fun startStream(request: StartRequest) {
        try {
            val intent = Intent(this, StreamService::class.java)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_FRONT_CAMERA, request.frontCamera)
                .putExtra(StreamService.EXTRA_USERNAME, request.username)
                .putExtra(StreamService.EXTRA_PASSWORD, request.password)
                .putExtra(StreamService.EXTRA_WIDTH, request.width)
                .putExtra(StreamService.EXTRA_HEIGHT, request.height)
                .putExtra(StreamService.EXTRA_FRAME_RATE, request.frameRate)
                .putExtra(StreamService.EXTRA_BIT_RATE, request.bitRate)
            ContextCompat.startForegroundService(this, intent)
            request.result.success(null)
        } catch (error: Exception) {
            request.result.error(
                "START_FAILED",
                error.message ?: "Unable to start the camera service.",
                null
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val request = pendingRequest ?: return
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermission(request)
                } else {
                    clearPendingStart()
                    request.result.error(
                        "CAMERA_PERMISSION_DENIED",
                        "Camera permission is required to stream.",
                        null
                    )
                }
            }
            NOTIFICATION_PERMISSION_REQUEST -> {
                clearPendingStart()
                startStream(request)
            }
        }
    }

    private fun clearPendingStart() {
        pendingStart = null
        pendingRequest = null
    }

    private fun publishStatus(status: Map<String, Any?>) {
        eventSink?.success(status)
        if (status["state"] == "live") maybePromptBatteryOptimization()
    }

    private fun maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            isFinishing ||
            isChangingConfigurations
        ) return
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(BATTERY_PROMPT_SHOWN, false)) return
        preferences.edit().putBoolean(BATTERY_PROMPT_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep streaming reliably")
            .setMessage(
                "Battery optimization can stop background camera streaming. " +
                    "Allow Phone RTSP to run without battery restrictions?"
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .show()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 7401
        private const val NOTIFICATION_PERMISSION_REQUEST = 7402
        private const val PREFERENCES = "phone_rtsp_preferences"
        private const val BATTERY_PROMPT_SHOWN = "battery_optimization_prompt_shown"
        private const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    }
}
