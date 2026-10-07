package com.phone_rtsp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private lateinit var streamer: RtspCameraStreamer
    private var eventSink: EventChannel.EventSink? = null
    private var pendingStart: MethodChannel.Result? = null
    private var pendingUsername = ""
    private var pendingPassword = ""

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        streamer = RtspCameraStreamer(applicationContext) { state, message ->
            runOnUiThread {
                if (state == "live" || state == "starting") {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                eventSink?.success(status(state, message))
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "phone_rtsp/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    eventSink = events
                    eventSink?.success(streamer.status())
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "phone_rtsp/streamer")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getStatus" -> result.success(streamer.status())
                    "start" -> requestCameraPermission(
                        call.argument<Boolean>("frontCamera") ?: false,
                        call.argument<String>("username").orEmpty().trim(),
                        call.argument<String>("password").orEmpty(),
                        call.argument<Int>("width") ?: 1920,
                        call.argument<Int>("height") ?: 1080,
                        call.argument<Int>("frameRate") ?: 25,
                        call.argument<Int>("bitRate") ?: 4_000_000,
                        result
                    )
                    "stop" -> {
                        streamer.stop()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun requestCameraPermission(
        frontCamera: Boolean,
        username: String,
        password: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        result: MethodChannel.Result
    ) {
        if (username.isEmpty() != password.isEmpty()) {
            result.error(
                "INVALID_CREDENTIALS",
                "Enter both a username and password, or leave both blank.",
                null
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startStream(
                frontCamera,
                username,
                password,
                width,
                height,
                frameRate,
                bitRate,
                result
            )
            return
        }

        if (pendingStart != null) {
            result.error("BUSY", "A camera permission request is already pending.", null)
            return
        }
        pendingStart = result
        pendingFrontCamera = frontCamera
        pendingUsername = username
        pendingPassword = password
        pendingWidth = width
        pendingHeight = height
        pendingFrameRate = frameRate
        pendingBitRate = bitRate
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    private var pendingFrontCamera = false
    private var pendingWidth = 1920
    private var pendingHeight = 1080
    private var pendingFrameRate = 25
    private var pendingBitRate = 4_000_000

    private fun startStream(
        frontCamera: Boolean,
        username: String,
        password: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        result: MethodChannel.Result
    ) {
        try {
            streamer.start(
                frontCamera,
                username,
                password,
                width,
                height,
                frameRate,
                bitRate
            )
            result.success(null)
        } catch (error: Exception) {
            result.error("START_FAILED", error.message ?: "Unable to start the camera service.", null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        val result = pendingStart
        pendingStart = null
        if (result == null) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startStream(
                pendingFrontCamera,
                pendingUsername,
                pendingPassword,
                pendingWidth,
                pendingHeight,
                pendingFrameRate,
                pendingBitRate,
                result
            )
        } else {
            result.error("CAMERA_PERMISSION_DENIED", "Camera permission is required to stream.", null)
        }
        pendingUsername = ""
        pendingPassword = ""
    }

    private fun status(state: String, message: String?): Map<String, Any?> =
        streamer.status().toMutableMap().apply {
            this["state"] = state
            this["message"] = message
        }

    override fun onDestroy() {
        if (::streamer.isInitialized) streamer.stop()
        super.onDestroy()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 7401
    }
}
