package com.phone_rtsp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

internal class EncodedStream(
    val path: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int
) {
    var codec: MediaCodec? = null
    var codecName: String? = null
    var surface: Surface? = null
    @Volatile var sps: ByteArray? = null
    @Volatile var pps: ByteArray? = null
    @Volatile var enabled = true
    var drainThread: Thread? = null

    fun requestSyncFrame() {
        if (!enabled) return
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        } catch (error: Exception) {
            Log.w("PhoneRtspStreamer", "Could not request a new H.264 keyframe.", error)
        }
    }
}

internal class RtspCameraStreamer(
    private val context: Context,
    private val onState: (String, String?) -> Unit
) {
    @Volatile private var streaming = false
    @Volatile private var frontCamera = false
    @Volatile private var serverReady = false
    @Volatile private var cameraReady = false
    @Volatile private var lastError: String? = null
    @Volatile private var substreamWarning: String? = null
    @Volatile private var mainWidth = DEFAULT_WIDTH
    @Volatile private var mainHeight = DEFAULT_HEIGHT
    @Volatile private var mainFrameRate = DEFAULT_FRAME_RATE
    @Volatile private var mainBitRate = DEFAULT_BIT_RATE

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var streams = emptyList<EncodedStream>()
    private var overlayRenderer: OverlayRenderer? = null
    private var server: RtspServer? = null

    @Synchronized
    fun start(
        useFrontCamera: Boolean,
        username: String,
        password: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int
    ) {
        if (streaming) return
        require((width to height) in SUPPORTED_MAIN_SIZES) {
            "Choose a supported main stream resolution."
        }
        require(frameRate in SUPPORTED_FRAME_RATES) {
            "Choose a frame rate between 10 and 30 FPS."
        }
        require(bitRate in MIN_BIT_RATE..MAX_BIT_RATE) {
            "Main stream bitrate must be between 1 and 4 Mbps."
        }
        streaming = true
        frontCamera = useFrontCamera
        mainWidth = width
        mainHeight = height
        mainFrameRate = frameRate
        mainBitRate = bitRate
        serverReady = false
        cameraReady = false
        lastError = null
        substreamWarning = null
        emit("starting")

        try {
            cameraThread = HandlerThread("rtsp-camera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            prepareEncoders()
            overlayRenderer = OverlayRenderer(context, streams) { message ->
                fail(message)
            }.also { it.start(mainWidth, mainHeight) }
            server = RtspServer(
                onListening = {
                    serverReady = true
                    updateReadyState()
                },
                onFailure = { message -> fail(message) },
                streams = streams.associateBy { it.path },
                username = username,
                password = password,
            ).also { it.start() }
            openCamera()
        } catch (error: Exception) {
            cleanup()
            streaming = false
            lastError = error.message ?: "Unable to initialize the camera stream."
            emit("error", lastError)
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (!streaming && server == null && cameraThread == null) return
        streaming = false
        lastError = null
        cleanup()
        emit("stopped")
    }

    fun status(): Map<String, Any?> = mapOf(
        "state" to when {
            lastError != null -> "error"
            !streaming -> "stopped"
            serverReady && cameraReady -> "live"
            else -> "starting"
        },
        "message" to (lastError ?: substreamWarning),
        "address" to localIpv4Address(),
        "frontCamera" to frontCamera,
        "width" to mainWidth,
        "height" to mainHeight,
        "frameRate" to mainFrameRate,
        "bitRate" to mainBitRate
    )

    private fun prepareEncoders() {
        val cameraId = findCameraId()
        val characteristics = cameraManager().getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val cameraFacing = when (
            characteristics.get(CameraCharacteristics.LENS_FACING)
        ) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            else -> "external/unknown"
        }
        Log.i(
            TAG,
            "Camera id=$cameraId facing=$cameraFacing sensorOrientation=$sensorOrientation degrees."
        )
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: throw IllegalStateException("This camera does not expose a video output.")
        val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.takeIf { it.isNotEmpty() }
            ?: map.getOutputSizes(MediaCodec::class.java)?.takeIf { it.isNotEmpty() }
            ?: map.getOutputSizes(ImageFormat.PRIVATE)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Unable to read supported camera resolutions.")
        val size = supportedSizes.firstOrNull {
            it.width == mainWidth && it.height == mainHeight
        }
            ?: throw IllegalStateException(
                "This camera does not support ${mainWidth} × $mainHeight video output."
            )
        val substreamSizes = supportedSizes
            .filter { it.width <= SUB_MAX_WIDTH }
            .filter { it.width * it.height < size.width * size.height }
        val mainAspectRatio = size.width.toDouble() / size.height
        val subSize = substreamSizes
            .filter {
                val subAspectRatio = it.width.toDouble() / it.height
                abs(subAspectRatio - mainAspectRatio) / mainAspectRatio <=
                    SUB_ASPECT_RATIO_TOLERANCE
            }
            .maxByOrNull { it.width * it.height }
            ?: scaledSubstreamSize(size)

        val mainStream = createEncoder(
            "/live",
            size.width,
            size.height,
            mainFrameRate,
            mainBitRate,
            iFrameIntervalSeconds = 2
        )
        streams = listOf(mainStream)
        val subStream = if (subSize == null) {
            val warning =
                "Main stream is available, but the camera has no lower-resolution substream output."
            substreamWarning = warning
            Log.w(TAG, warning)
            null
        } else try {
            Log.i(
                TAG,
                "Starting /sub encoder at ${subSize.width}x${subSize.height}, " +
                    "$mainFrameRate FPS, $SUB_BIT_RATE bps."
            )
            createEncoder(
                "/sub",
                subSize.width,
                subSize.height,
                mainFrameRate,
                SUB_BIT_RATE,
                iFrameIntervalSeconds = 1,
                lowLatency = true
            )
        } catch (error: Exception) {
            val warning =
                "Main stream is available, but the substream encoder failed: " +
                    (error.message ?: "unsupported encoder configuration.")
            substreamWarning = warning
            Log.w(TAG, warning, error)
            null
        }
        streams = if (subStream == null) listOf(mainStream) else listOf(mainStream, subStream)
        streams.forEach { stream ->
            Log.i(
                TAG,
                "Prepared ${stream.path} encoder ${stream.codecName ?: "unknown"} at " +
                    "${stream.width}x${stream.height}, ${stream.frameRate} FPS, " +
                    "${stream.bitRate} bps."
            )
            stream.drainThread = Thread(
                { drainEncoder(stream) },
                "rtsp-encoder-${stream.path.substring(1)}"
            ).also { it.start() }
        }
    }

    private fun createEncoder(
        path: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        iFrameIntervalSeconds: Int,
        lowLatency: Boolean = false
    ): EncodedStream {
        val stream = EncodedStream(path, width, height, frameRate, bitRate)
        fun createFormat(useCompatibilityHints: Boolean) =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
                if (useCompatibilityHints) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
            }
        var codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        stream.codec = codec
        try {
            try {
                codec.configure(
                    createFormat(useCompatibilityHints = true),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            } catch (configurationError: Exception) {
                try {
                    codec.release()
                } catch (_: Exception) {
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                stream.codec = codec
                try {
                    codec.configure(
                        createFormat(useCompatibilityHints = false),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                    )
                } catch (fallbackError: Exception) {
                    fallbackError.addSuppressed(configurationError)
                    throw fallbackError
                }
            }
            stream.surface = codec.createInputSurface()
            stream.codecName = codec.name
            codec.start()
        } catch (error: Exception) {
            stream.surface?.release()
            stream.surface = null
            try {
                codec.release()
            } catch (_: Exception) {
            }
            stream.codec = null
            throw error
        }
        return stream
    }

    private fun scaledSubstreamSize(mainSize: Size): Size {
        val scale = minOf(
            SUB_MAX_WIDTH.toDouble() / mainSize.width,
            SUB_MAX_HEIGHT.toDouble() / mainSize.height,
            if (mainSize.width <= SUB_MAX_WIDTH && mainSize.height <= SUB_MAX_HEIGHT) {
                0.75
            } else {
                1.0
            }
        )
        val width = maxOf(2, (mainSize.width * scale).toInt() / 2 * 2)
        val height = maxOf(2, (mainSize.height * scale).toInt() / 2 * 2)
        return Size(width, height)
    }

    private fun findCameraId(): String {
        val desiredFacing = if (frontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager().cameraIdList.firstOrNull { id ->
            cameraManager().getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == desiredFacing
        } ?: throw IllegalStateException(
            if (frontCamera) "No front-facing camera is available."
            else "No rear-facing camera is available."
        )
    }

    private fun openCamera() {
        val id = findCameraId()
        cameraManager().openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!streaming) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("The camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail("The camera failed to open (error $error).")
                }
            },
            cameraHandler
        )
    }

    private fun createCaptureSession(camera: CameraDevice) {
        configureCaptureSession(camera, streams)
    }

    private fun configureCaptureSession(
        camera: CameraDevice,
        targetStreams: List<EncodedStream>
    ) {
        val handler = cameraHandler ?: return
        val cameraSurface = overlayRenderer?.inputSurface
        if (cameraSurface == null || targetStreams.isEmpty()) {
            fail("The OpenGL camera input surface is unavailable.")
            return
        }
        val outputs = listOf(cameraSurface)
        try {
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!streaming) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                .apply {
                                    outputs.forEach(::addTarget)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        targetFpsRange(camera, mainFrameRate)
                                    )
                                }
                            session.setRepeatingRequest(request.build(), null, handler)
                            cameraReady = true
                            updateReadyState()
                        } catch (error: Exception) {
                            session.close()
                            if (targetStreams.any { it.path == "/sub" }) {
                                disableSubstream(
                                    camera,
                                    error.message
                                        ?: "Unable to start the dual-output camera capture."
                                )
                            } else {
                                fail(error.message ?: "Unable to start camera capture.")
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (targetStreams.any { it.path == "/sub" }) {
                            disableSubstream(
                                camera,
                                "The camera could not configure both main and substream outputs."
                            )
                        } else {
                            fail("The camera could not configure the main stream output.")
                        }
                    }
                },
                handler
            )
        } catch (error: Exception) {
            if (targetStreams.any { it.path == "/sub" }) {
                disableSubstream(
                    camera,
                    error.message ?: "Unable to configure dual-output camera capture."
                )
            } else {
                fail(error.message ?: "Unable to configure camera capture.")
            }
        }
    }

    private fun disableSubstream(camera: CameraDevice, reason: String) {
        val substream = streams.firstOrNull { it.path == "/sub" }
        if (substream == null) {
            fail(reason)
            return
        }
        substreamWarning = "Main stream is live, but the substream is unavailable: $reason"
        Log.w(TAG, substreamWarning ?: reason)
        val remainingStreams = streams.filterNot { it === substream }
        try {
            overlayRenderer?.setStreams(remainingStreams)
        } catch (error: Exception) {
            fail(error.message ?: "Unable to remove the failed substream output.")
            return
        }
        server?.removeStream("/sub")
        streams = remainingStreams
        releaseStream(substream)
        cameraHandler?.post {
            if (streaming) configureCaptureSession(camera, streams)
        }
    }

    private fun targetFpsRange(camera: CameraDevice, frameRate: Int): Range<Int> {
        val activeId = camera.id
        val ranges = cameraManager().getCameraCharacteristics(activeId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        return ranges.firstOrNull { it.lower == frameRate && it.upper == frameRate }
            ?: ranges.filter { it.contains(frameRate) }
                .minByOrNull { it.upper - it.lower }
            ?: ranges.minByOrNull { abs(it.upper - frameRate) }
            ?: Range(15, 30)
    }

    private fun drainEncoder(stream: EncodedStream) {
        val info = MediaCodec.BufferInfo()
        var loggedFrames = 0
        var loggedBytes = 0L
        var logWindowStartedAt = System.nanoTime()
        while (streaming && stream.enabled) {
            val codec = stream.codec ?: break
            try {
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        readCodecConfig(stream, codec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val nals = splitNals(bytes)
                            if (nals.isNotEmpty()) {
                                loggedFrames++
                                loggedBytes += bytes.size
                                server?.sendAccessUnit(
                                    stream,
                                    nals,
                                    info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                )
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                val now = System.nanoTime()
                if (now - logWindowStartedAt >= ENCODER_LOG_INTERVAL_NS) {
                    val elapsedSeconds = (now - logWindowStartedAt) / 1_000_000_000.0
                    Log.i(
                        TAG,
                        "${stream.path} encoder ${stream.codecName ?: "unknown"}: " +
                            "${"%.1f".format(java.util.Locale.US, loggedFrames / elapsedSeconds)} FPS, " +
                            "$loggedBytes bytes in ${"%.1f".format(java.util.Locale.US, elapsedSeconds)}s."
                    )
                    loggedFrames = 0
                    loggedBytes = 0
                    logWindowStartedAt = now
                }
            } catch (error: Exception) {
                if (streaming && stream.enabled) {
                    if (stream.path == "/sub") {
                        val message =
                            error.message ?: "The substream encoder stopped unexpectedly."
                        Log.w(TAG, message, error)
                        val camera = cameraDevice
                        if (camera != null) {
                            cameraHandler?.post { disableSubstream(camera, message) }
                        } else {
                            fail(message)
                        }
                    } else {
                        fail(error.message ?: "The video encoder stopped unexpectedly.")
                    }
                }
                break
            }
        }
    }

    private fun readCodecConfig(stream: EncodedStream, format: MediaFormat) {
        stream.sps = format.getByteBuffer("csd-0")
            ?.let { splitNals(bufferBytes(it)).firstOrNull() }
        stream.pps = format.getByteBuffer("csd-1")
            ?.let { splitNals(bufferBytes(it)).firstOrNull() }
        if (stream.pps == null) {
            val config = format.getByteBuffer("csd-0")?.let { splitNals(bufferBytes(it)) }
            stream.sps = config?.firstOrNull {
                it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7
            } ?: stream.sps
            stream.pps = config?.firstOrNull {
                it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8
            } ?: stream.pps
        }
    }

    private fun bufferBytes(source: ByteBuffer): ByteArray {
        val copy = source.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        fun startCodeAt(index: Int): Int = when {
            index + 3 < data.size && data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> 4
            index + 2 < data.size && data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
            else -> 0
        }

        val starts = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (index < data.size) {
            val length = startCodeAt(index)
            if (length > 0) {
                starts.add(Pair(index, length))
                index += length
            } else {
                index++
            }
        }
        if (starts.isEmpty()) {
            val configurationNals = parseAvcConfigurationRecord(data)
            if (configurationNals.isNotEmpty()) return configurationNals
            val lengthPrefixedNals = parseLengthPrefixedNals(data)
            if (lengthPrefixedNals.isNotEmpty()) return lengthPrefixedNals
            return if (data.isNotEmpty()) listOf(data) else emptyList()
        }

        return starts.mapIndexedNotNull { position, item ->
            val start = item.first + item.second
            val end = if (position + 1 < starts.size) starts[position + 1].first else data.size
            if (start < end) data.copyOfRange(start, end) else null
        }
    }

    private fun parseLengthPrefixedNals(data: ByteArray): List<ByteArray> {
        for (lengthBytes in intArrayOf(4, 2, 1)) {
            val result = ArrayList<ByteArray>()
            var offset = 0
            var valid = true
            while (offset < data.size) {
                if (offset + lengthBytes > data.size) {
                    valid = false
                    break
                }
                var nalLength = 0
                repeat(lengthBytes) { index ->
                    nalLength = (nalLength shl 8) or (data[offset + index].toInt() and 0xff)
                }
                offset += lengthBytes
                if (nalLength <= 0 || offset + nalLength > data.size) {
                    valid = false
                    break
                }
                val nal = data.copyOfRange(offset, offset + nalLength)
                val nalType = nal[0].toInt() and 0x1f
                if (nalType !in 1..23) {
                    valid = false
                    break
                }
                result.add(nal)
                offset += nalLength
            }
            if (valid && offset == data.size && result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun parseAvcConfigurationRecord(data: ByteArray): List<ByteArray> {
        if (data.size < 7 || data[0].toInt() != 1) return emptyList()
        val nals = ArrayList<ByteArray>()
        var offset = 6
        val sequenceParameterSets = data[5].toInt() and 0x1f
        repeat(sequenceParameterSets) {
            if (offset + 2 > data.size) return emptyList()
            val length = ((data[offset].toInt() and 0xff) shl 8) or
                (data[offset + 1].toInt() and 0xff)
            offset += 2
            if (length <= 0 || offset + length > data.size) return emptyList()
            nals.add(data.copyOfRange(offset, offset + length))
            offset += length
        }
        if (offset >= data.size) return nals
        val pictureParameterSets = data[offset++].toInt() and 0xff
        repeat(pictureParameterSets) {
            if (offset + 2 > data.size) return emptyList()
            val length = ((data[offset].toInt() and 0xff) shl 8) or
                (data[offset + 1].toInt() and 0xff)
            offset += 2
            if (length <= 0 || offset + length > data.size) return emptyList()
            nals.add(data.copyOfRange(offset, offset + length))
            offset += length
        }
        return nals
    }

    private fun updateReadyState() {
        if (streaming && serverReady && cameraReady) emit("live", substreamWarning)
    }

    private fun fail(message: String) {
        if (!streaming) return
        lastError = message
        streaming = false
        cleanup()
        emit("error", message)
    }

    @Synchronized
    private fun cleanup() {
        server?.stop()
        server = null
        cameraReady = false
        serverReady = false
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        overlayRenderer?.stop()
        overlayRenderer = null
        streams.forEach(::releaseStream)
        streams = emptyList()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun releaseStream(stream: EncodedStream) {
        stream.enabled = false
        val drainThread = stream.drainThread
        if (drainThread != null && drainThread !== Thread.currentThread()) {
            try {
                drainThread.join(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        stream.surface?.release()
        stream.surface = null
        try {
            stream.codec?.stop()
        } catch (_: Exception) {
        }
        try {
            stream.codec?.release()
        } catch (_: Exception) {
        }
        stream.codec = null
    }

    private fun emit(state: String, message: String? = null) {
        onState(state, message)
    }

    private fun cameraManager() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun localIpv4Address(): String? {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null &&
                connectivityManager.getNetworkCapabilities(activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) {
                val wifiAddress = connectivityManager.getLinkProperties(activeNetwork)
                    ?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                if (wifiAddress != null) return wifiAddress
            }
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val addresses = interfaces.toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            return addresses.firstOrNull {
                val address = it.hostAddress ?: return@firstOrNull false
                address.startsWith("192.168.") ||
                    address.startsWith("10.") ||
                    address.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
            }?.hostAddress ?: addresses.firstOrNull()?.hostAddress
        } catch (_: SocketException) {
            return null
        }
    }

    companion object {
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_FRAME_RATE = 25
        private const val DEFAULT_BIT_RATE = 4_000_000
        private const val MIN_BIT_RATE = 1_000_000
        private const val MAX_BIT_RATE = 4_000_000
        private const val SUB_MAX_WIDTH = 640
        private const val SUB_MAX_HEIGHT = 480
        private const val SUB_BIT_RATE = 1_000_000
        private const val SUB_ASPECT_RATIO_TOLERANCE = 0.01
        private const val ENCODER_LOG_INTERVAL_NS = 5_000_000_000L
        private const val TAG = "PhoneRtspStreamer"
        private val SUPPORTED_MAIN_SIZES = setOf(1920 to 1080, 1280 to 720, 640 to 480)
        private val SUPPORTED_FRAME_RATES = setOf(10, 15, 20, 25, 30)
    }
}

private class RtspServer(
    private val onListening: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val streams: Map<String, EncodedStream>,
    private val username: String,
    private val password: String
) {
    @Volatile private var running = false
    @Volatile private var listener: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<Client>()
    private val streamMap = java.util.concurrent.ConcurrentHashMap(streams)
    private var serverThread: Thread? = null
    private val nonce = ByteArray(24).apply { SecureRandom().nextBytes(this) }.let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private val highestNonceCounts = HashMap<String, Long>()

    fun start() {
        running = true
        serverThread = Thread({
            try {
                val socket = ServerSocket(PORT)
                listener = socket
                onListening()
                while (running) {
                    val clientSocket = socket.accept()
                    Thread({ handleClient(clientSocket) }, "rtsp-client").start()
                }
            } catch (error: Exception) {
                if (running) onFailure(
                    if (error is java.net.BindException) {
                        "Port 8554 is already in use. Close the other RTSP server and retry."
                    } else {
                        error.message ?: "Unable to listen on RTSP port 8554."
                    }
                )
            }
        }, "rtsp-server").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            listener?.close()
        } catch (_: Exception) {
        }
        listener = null
        clients.forEach { it.close() }
        clients.clear()
    }

    fun removeStream(path: String) {
        streamMap.remove(path)
    }

    fun sendAccessUnit(
        stream: EncodedStream,
        nals: List<ByteArray>,
        presentationTimeUs: Long,
        keyFrame: Boolean
    ) {
        clients.forEach { client ->
            if (!client.playing || client.stream !== stream) return@forEach
            val (sequence, timestamp, ssrc) = client.nextRtpValues(presentationTimeUs)
            var nextSequence = sequence
            val output = ArrayList<ByteArray>()
            if (keyFrame) {
                stream.sps?.let { output.add(it) }
                stream.pps?.let { output.add(it) }
            }
            output.addAll(nals.filter {
                it.isNotEmpty() && (it[0].toInt() and 0x1f) != 7 &&
                    (it[0].toInt() and 0x1f) != 8
            })
            if (output.isEmpty()) return@forEach

            try {
                output.forEachIndexed { index, nal ->
                    val packets = packetize(
                        nal,
                        timestamp,
                        ssrc,
                        nextSequence,
                        index == output.lastIndex
                    )
                    nextSequence = (nextSequence + packets.size) and 0xffff
                    packets.forEach(client::sendRtp)
                }
                client.updateSequence(nextSequence)
            } catch (_: IOException) {
                clients.remove(client)
                client.close()
            }
        }
    }

    private fun packetize(
        nal: ByteArray,
        timestamp: Int,
        ssrc: Int,
        initialSequence: Int,
        lastNal: Boolean
    ): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val maxPayload = 1200
        if (nal.size <= maxPayload) {
            packets.add(rtpPacket(nal, timestamp, ssrc, initialSequence, lastNal))
            return packets
        }

        val nalHeader = nal[0].toInt()
        val fuIndicator = (nalHeader and 0xe0) or 28
        val nalType = nalHeader and 0x1f
        val chunkSize = maxPayload - 2
        var offset = 1
        var sequence = initialSequence
        while (offset < nal.size) {
            val length = minOf(chunkSize, nal.size - offset)
            val isStart = offset == 1
            val isEnd = offset + length >= nal.size
            val payload = ByteArray(length + 2)
            payload[0] = fuIndicator.toByte()
            payload[1] = (nalType or (if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0)).toByte()
            System.arraycopy(nal, offset, payload, 2, length)
            packets.add(
                rtpPacket(
                    payload,
                    timestamp,
                    ssrc,
                    sequence,
                    lastNal && isEnd
                )
            )
            sequence = (sequence + 1) and 0xffff
            offset += length
        }
        return packets
    }

    private fun rtpPacket(
        payload: ByteArray,
        timestamp: Int,
        ssrc: Int,
        sequence: Int,
        marker: Boolean
    ): ByteArray = ByteArray(12 + payload.size).also { packet ->
        packet[0] = 0x80.toByte()
        packet[1] = (96 or if (marker) 0x80 else 0).toByte()
        packet[2] = (sequence ushr 8).toByte()
        packet[3] = sequence.toByte()
        packet[4] = (timestamp ushr 24).toByte()
        packet[5] = (timestamp ushr 16).toByte()
        packet[6] = (timestamp ushr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (ssrc ushr 24).toByte()
        packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte()
        packet[11] = ssrc.toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 120_000
        val client = Client(socket)
        val input = BufferedInputStream(socket.getInputStream())
        var disconnectReason = "peer closed the connection"
        try {
            while (running && !socket.isClosed) {
                input.mark(1)
                val firstByte = input.read()
                if (firstByte < 0) break
                input.reset()
                if (firstByte == '$'.code) {
                    input.read()
                    val channel = readByte(input)
                    val frameLength = (readByte(input) shl 8) or readByte(input)
                    skipBytes(input, frameLength)
                    Log.i(TAG, "Received interleaved RTCP/RTP frame on channel $channel.")
                    continue
                }

                val requestLine = readAsciiLine(input) ?: break
                if (requestLine.isBlank()) continue
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                Log.i(
                    TAG,
                    "Request: $requestLine; Transport=${headers["transport"] ?: "(none)"}; " +
                        "User-Agent=${headers["user-agent"] ?: "(none)"}"
                )
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                skipBytes(input, contentLength)

                val parts = requestLine.split(' ')
                if (parts.size < 2) continue
                val method = parts[0].uppercase()
                val requestUrl = parts[1]
                val cseq = headers["cseq"] ?: "1"
                if (!client.isAuthorized(method, requestUrl, headers["authorization"])) {
                    client.respond(
                        cseq,
                        401,
                        "Unauthorized",
                        extraHeaders = challengeHeaders()
                    )
                    continue
                }
                when (method) {
                    "OPTIONS" -> client.respond(
                        cseq,
                        200,
                        "OK",
                        mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER")
                    )
                    "DESCRIBE" -> {
                        val requestedPath = pathOf(requestUrl).orEmpty()
                        val stream = streamMap[requestedPath]
                            ?: streamMap[if (requestedPath.contains("sub", ignoreCase = true)) {
                                "/sub"
                            } else {
                                "/live"
                            }]
                        if (stream == null) {
                            client.respond(cseq, 404, "Not Found")
                            continue
                        }
                        client.stream = stream
                        val sdp = createSdp(socket.localAddress, stream)
                        if (sdp == null) {
                            client.respond(cseq, 503, "Service Unavailable")
                        } else {
                            client.respond(
                                cseq,
                                200,
                                "OK",
                                mapOf(
                                    "Content-Base" to "${requestUrl.trimEnd('/')}/",
                                    "Content-Type" to "application/sdp"
                                ),
                                sdp
                            )
                        }
                    }
                    "SETUP" -> {
                        val transport = headers["transport"]
                        if (client.stream == null || transport == null ||
                            !client.setup(transport)
                        ) {
                            client.respond(cseq, 461, "Unsupported Transport")
                        } else {
                            clients.add(client)
                            client.respond(
                                cseq,
                                200,
                                "OK",
                                mapOf(
                                    "Session" to client.sessionId,
                                    "Transport" to client.transportResponse()
                                )
                            )
                        }
                    }
                    "PLAY" -> {
                        if (!client.isSetup) {
                            client.respond(cseq, 454, "Session Not Found")
                        } else {
                            client.respond(
                                cseq,
                                200,
                                "OK",
                                mapOf(
                                    "Session" to client.sessionId,
                                    "Range" to "npt=0.000-",
                                    "RTP-Info" to "url=${requestUrl.trimEnd('/')}/trackID=0;seq=${client.sequence}"
                                )
                            )
                            client.playing = true
                            client.stream?.requestSyncFrame()
                        }
                    }
                    "PAUSE" -> {
                        client.playing = false
                        client.respond(cseq, 200, "OK", mapOf("Session" to client.sessionId))
                    }
                    "GET_PARAMETER" -> client.respond(
                        cseq,
                        200,
                        "OK",
                        mapOf("Session" to client.sessionId)
                    )
                    "TEARDOWN" -> {
                        client.playing = false
                        clients.remove(client)
                        client.respond(cseq, 200, "OK", mapOf("Session" to client.sessionId))
                        disconnectReason = "TEARDOWN requested"
                        break
                    }
                    else -> client.respond(cseq, 405, "Method Not Allowed")
                }
            }
        } catch (error: Exception) {
            disconnectReason = error.message ?: error.javaClass.simpleName
        } finally {
            clients.remove(client)
            Log.i(TAG, "Client ${socket.inetAddress.hostAddress} disconnected: $disconnectReason")
            client.close()
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (line.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
            if (line.size() > MAX_RTSP_LINE_BYTES) {
                throw IOException("RTSP request line or header exceeds the size limit.")
            }
        }
        return line.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun readByte(input: BufferedInputStream): Int =
        input.read().takeIf { it >= 0 } ?: throw IOException("Incomplete interleaved frame header.")

    private fun skipBytes(input: BufferedInputStream, count: Int) {
        if (count < 0) throw IOException("Invalid RTSP body length.")
        var remaining = count
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw IOException("Incomplete RTSP body or interleaved frame.")
            remaining -= read
        }
    }

    private fun challengeHeaders(): List<Pair<String, String>> {
        if (username.isEmpty()) return emptyList()
        return listOf(
            "WWW-Authenticate" to
                "Digest realm=\"$REALM\", nonce=\"$nonce\", qop=\"auth\", algorithm=MD5",
            "WWW-Authenticate" to "Basic realm=\"$REALM\", charset=\"UTF-8\""
        )
    }

    private fun authorize(authorization: String?, method: String, requestUrl: String): Boolean {
        if (username.isEmpty()) return true
        if (authorization == null) return false

        if (authorization.startsWith("Basic ", ignoreCase = true)) {
            val decoded = try {
                String(
                    Base64.decode(authorization.substringAfter(' ').trim(), Base64.DEFAULT),
                    Charsets.UTF_8
                )
            } catch (_: IllegalArgumentException) {
                return false
            }
            val separator = decoded.indexOf(':')
            if (separator < 0) return false
            val suppliedUsername = decoded.substring(0, separator)
            val suppliedPassword = decoded.substring(separator + 1)
            return MessageDigest.isEqual(
                "$username:$password".toByteArray(Charsets.UTF_8),
                "$suppliedUsername:$suppliedPassword".toByteArray(Charsets.UTF_8)
            )
        }

        if (!authorization.startsWith("Digest ", ignoreCase = true)) return false
        val values = parseDigestAuthorization(authorization.substringAfter(' '))
        if (values["username"] != username ||
            values["realm"] != REALM ||
            values["nonce"] != nonce ||
            values["uri"] != requestUrl ||
            !values["algorithm"].orEmpty().equals("MD5", ignoreCase = true) &&
                values.containsKey("algorithm")
        ) {
            return false
        }

        val suppliedResponse = values["response"] ?: return false
        val qop = values["qop"]
        val a1 = md5Hex("$username:$REALM:$password")
        val a2 = md5Hex("$method:$requestUrl")
        val expected = if (qop.equals("auth", ignoreCase = true)) {
            val nonceCount = values["nc"] ?: return false
            val clientNonce = values["cnonce"] ?: return false
            if (!nonceCount.matches(Regex("[0-9a-fA-F]{8}"))) return false
            md5Hex("$a1:$nonce:$nonceCount:$clientNonce:auth:$a2")
        } else {
            if (qop != null) return false
            md5Hex("$a1:$nonce:$a2")
        }
        if (!MessageDigest.isEqual(
                expected.lowercase().toByteArray(Charsets.US_ASCII),
                suppliedResponse.lowercase().toByteArray(Charsets.US_ASCII)
            )
        ) {
            return false
        }

        if (qop.equals("auth", ignoreCase = true)) {
            val clientNonce = values["cnonce"] ?: return false
            val nonceCount = values["nc"]?.toLongOrNull(16) ?: return false
            val key = "$username:$clientNonce"
            synchronized(highestNonceCounts) {
                if (nonceCount <= (highestNonceCounts[key] ?: 0L)) return false
                highestNonceCounts[key] = nonceCount
            }
        }
        return true
    }

    private fun parseDigestAuthorization(value: String): Map<String, String> {
        val result = HashMap<String, String>()
        val matcher = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            .findAll(value)
        matcher.forEach { match ->
            result[match.groupValues[1].lowercase()] =
                match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
        return result
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun pathOf(url: String): String? = try {
        URI(url).path?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun createSdp(localAddress: InetAddress, stream: EncodedStream): String? {
        val sps = stream.sps
        val pps = stream.pps
        if (sps == null || pps == null || sps.size < 4) return null
        val profile = sps.copyOfRange(1, 4).joinToString("") { "%02x".format(it) }
        val sprop = "${Base64.encodeToString(sps, Base64.NO_WRAP)}," +
            Base64.encodeToString(pps, Base64.NO_WRAP)
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 ${localAddress.hostAddress}\r\n")
            append("s=Phone Camera ${stream.path.substring(1)}\r\n")
            append("c=IN IP4 ${localAddress.hostAddress}\r\n")
            append("t=0 0\r\n")
            append("a=control:*\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=$sprop\r\n")
            append("a=framerate:${stream.frameRate}\r\n")
            append("a=control:trackID=0\r\n")
        }
    }

    private inner class Client(private val socket: Socket) {
        private val output = socket.getOutputStream()
        private val writeLock = Any()
        private var tcpTransport = false
        private var rtpChannel = 0
        private var rtpSocket: DatagramSocket? = null
        private var rtcpSocket: DatagramSocket? = null
        private var clientRtpPort = 0
        private var clientRtcpPort = 0
        @Volatile private var sequenceNumber = (System.nanoTime() and 0xffff).toInt()
        private val ssrc = System.nanoTime().toInt()
        val sessionId: String = java.lang.Long.toHexString(System.nanoTime())
        @Volatile var playing = false
        @Volatile var stream: EncodedStream? = null
        var isSetup = false
            private set
        val sequence: Int get() = sequenceNumber

        fun isAuthorized(method: String, requestUrl: String, authorization: String?): Boolean =
            authorize(authorization, method, requestUrl)

        fun setup(transport: String): Boolean {
            if (transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
                val channels = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE)
                    .find(transport)
                rtpChannel = channels?.groupValues?.get(1)?.toIntOrNull() ?: 0
                tcpTransport = true
                isSetup = true
                return true
            }
            val ports = Regex("client_port=(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE)
                .find(transport) ?: return false
            clientRtpPort = ports.groupValues[1].toIntOrNull() ?: return false
            clientRtcpPort = ports.groupValues[2].toIntOrNull() ?: clientRtpPort + 1
            return try {
                rtpSocket = DatagramSocket()
                rtcpSocket = DatagramSocket()
                isSetup = true
                true
            } catch (_: SocketException) {
                close()
                false
            }
        }

        fun transportResponse(): String {
            return if (tcpTransport) {
                "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-${rtpChannel + 1}"
            } else {
                "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;" +
                    "server_port=${rtpSocket?.localPort}-${rtcpSocket?.localPort}"
            }
        }

        fun respond(
            cseq: String,
            code: Int,
            reason: String,
            headers: Map<String, String> = emptyMap(),
            body: String = "",
            extraHeaders: List<Pair<String, String>> = emptyList()
        ) {
            Log.i(TAG, "Response: RTSP/1.0 $code $reason (CSeq $cseq)")
            val bytes = body.toByteArray(Charsets.US_ASCII)
            val response = buildString {
                append("RTSP/1.0 $code $reason\r\n")
                append("CSeq: $cseq\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${bytes.size}\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            synchronized(writeLock) {
                output.write(response)
                if (bytes.isNotEmpty()) output.write(bytes)
                output.flush()
            }
        }

        fun nextRtpValues(presentationTimeUs: Long): Triple<Int, Int, Int> =
            Triple(sequenceNumber, ((presentationTimeUs * 90L) / 1000L).toInt(), ssrc)

        fun updateSequence(next: Int) {
            sequenceNumber = next
        }

        fun sendRtp(packet: ByteArray) {
            if (tcpTransport) {
                synchronized(writeLock) {
                    output.write('$'.code)
                    output.write(rtpChannel)
                    output.write((packet.size ushr 8) and 0xff)
                    output.write(packet.size and 0xff)
                    output.write(packet)
                    output.flush()
                }
            } else {
                val target = socket.inetAddress
                rtpSocket?.send(DatagramPacket(packet, packet.size, target, clientRtpPort))
            }
        }

        fun close() {
            playing = false
            try {
                rtpSocket?.close()
                rtcpSocket?.close()
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val PORT = 8554
        private const val MAX_RTSP_LINE_BYTES = 16 * 1024
        private const val REALM = "Phone RTSP"
        private const val TAG = "PhoneRtspServer"
    }
}
