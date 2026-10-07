package com.phone_rtsp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal class OverlayRenderer(
    private val context: Context,
    private val streams: List<EncodedStream>,
    private val onFailure: (String) -> Unit
) {
    private data class Output(
        val stream: EncodedStream,
        val eglSurface: android.opengl.EGLSurface,
        var labelTexture: Int = 0,
        var labelText: String? = null,
        var labelWidth: Int = 0,
        var labelHeight: Int = 0
    )

    private val glThread = HandlerThread("rtsp-overlay-gl")
    private lateinit var glHandler: Handler
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var config: android.opengl.EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var cameraTexture = 0
    private var cameraTextureSurface: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var cameraProgram = 0
    private var labelProgram = 0
    private var outputs = emptyList<Output>()
    @Volatile private var stopped = false
    private var lastLabelSecond = Long.MIN_VALUE
    private var label = ""
    private var transformMatrixLogged = false
    private var cameraWidth = 0
    private var cameraHeight = 0
    private val transformMatrix = FloatArray(16)
    private val cameraPositionBuffer = directFloatBuffer(FloatArray(8))
    private val fullscreenTextureBuffer = directFloatBuffer(FULLSCREEN_TEX_COORDS)
    private val labelPositionBuffer = directFloatBuffer(FloatArray(8))
    private val labelTextureBuffer = directFloatBuffer(LABEL_TEX_COORDS)

    val inputSurface: Surface
        get() = cameraSurface ?: error("The overlay camera surface is not initialized.")

    fun start(cameraWidth: Int, cameraHeight: Int) {
        this.cameraWidth = cameraWidth
        this.cameraHeight = cameraHeight
        Log.i(
            TAG,
            "Overlay camera buffer=${cameraWidth}x$cameraHeight; outputs=" +
                streams.joinToString { "${it.path}=${it.width}x${it.height}" }
        )
        glThread.start()
        glHandler = Handler(glThread.looper)
        try {
            runOnGlThreadAndWait {
                initializeEgl()
                cameraTexture = createExternalTexture()
                cameraTextureSurface = SurfaceTexture(cameraTexture).apply {
                    setDefaultBufferSize(cameraWidth, cameraHeight)
                    setOnFrameAvailableListener({ renderFrame() }, glHandler)
                }
                cameraSurface = Surface(cameraTextureSurface!!)
                cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
                labelProgram = createProgram(LABEL_VERTEX_SHADER, LABEL_FRAGMENT_SHADER)
                setOutputsOnGlThread(streams)
            }
        } catch (error: Exception) {
            stop()
            throw IllegalStateException("Unable to initialize the OpenGL video overlay.", error)
        }
    }

    fun setStreams(streams: List<EncodedStream>) {
        runOnGlThreadAndWait { setOutputsOnGlThread(streams) }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (glThread.isAlive) {
            try {
                runOnGlThreadAndWait {
                    outputs.forEach(::destroyOutput)
                    outputs = emptyList()
                    cameraSurface?.release()
                    cameraSurface = null
                    cameraTextureSurface?.release()
                    cameraTextureSurface = null
                    if (cameraTexture != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(cameraTexture), 0)
                        cameraTexture = 0
                    }
                    if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
                    if (labelProgram != 0) GLES20.glDeleteProgram(labelProgram)
                    cameraProgram = 0
                    labelProgram = 0
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(
                            display,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT
                        )
                        if (pbuffer != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(display, pbuffer)
                            pbuffer = EGL14.EGL_NO_SURFACE
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            EGL14.eglDestroyContext(display, eglContext)
                            eglContext = EGL14.EGL_NO_CONTEXT
                        }
                        EGL14.eglTerminate(display)
                        display = EGL14.EGL_NO_DISPLAY
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Error while releasing the OpenGL overlay.", error)
            }
            glThread.quitSafely()
            if (Looper.myLooper() != glThread.looper) {
                try {
                    glThread.join(2_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get an EGL display." }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            "Unable to initialize EGL."
        }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) &&
                count[0] > 0
        ) { "Unable to find a recordable EGL configuration." }
        config = configs[0] ?: error("EGL did not return a configuration.")
        eglContext = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create an EGL context." }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "Unable to create an EGL pbuffer." }
        makeCurrent(pbuffer)
    }

    private fun setOutputsOnGlThread(streams: List<EncodedStream>) {
        val remaining = outputs.toMutableList()
        val updated = ArrayList<Output>(streams.size)
        try {
            streams.forEach { stream ->
                val existing = remaining.firstOrNull { it.stream === stream }
                if (existing != null) {
                    remaining.remove(existing)
                    updated.add(existing)
                } else {
                    val surface = EGL14.eglCreateWindowSurface(
                        display,
                        config,
                        stream.surface ?: error("Encoder surface ${stream.path} is unavailable."),
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                    check(surface != EGL14.EGL_NO_SURFACE) {
                        "Unable to create an EGL surface for ${stream.path}."
                    }
                    updated.add(Output(stream, surface))
                }
            }
        } catch (error: Exception) {
            updated.filterNot { output -> outputs.any { it === output } }
                .forEach(::destroyOutput)
            throw error
        }
        if (remaining.isNotEmpty()) makeCurrent(pbuffer)
        remaining.forEach(::destroyOutput)
        outputs = updated
    }

    private fun renderFrame() {
        if (stopped) return
        try {
            val texture = cameraTextureSurface ?: return
            texture.updateTexImage()
            texture.getTransformMatrix(transformMatrix)
            if (!transformMatrixLogged) {
                Log.i(TAG, "SurfaceTexture transform matrix=${transformMatrix.joinToString()}")
                transformMatrixLogged = true
            }
            updateLabel()
            val timestamp = texture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
            outputs.forEach { output ->
                makeCurrent(output.eglSurface)
                GLES20.glViewport(0, 0, output.stream.width, output.stream.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawCamera(output.stream)
                drawLabel(output)
                check(
                    EGLExt.eglPresentationTimeANDROID(display, output.eglSurface, timestamp)
                ) { "Unable to set the ${output.stream.path} frame timestamp." }
                check(EGL14.eglSwapBuffers(display, output.eglSurface)) {
                    "Unable to deliver a frame to ${output.stream.path}."
                }
            }
        } catch (error: Exception) {
            if (!stopped) {
                Log.e(TAG, "OpenGL video frame rendering failed.", error)
                onFailure(error.message ?: "The OpenGL video overlay failed.")
            }
        }
    }

    private fun updateLabel() {
        val currentSecond = System.currentTimeMillis() / 1000
        if (currentSecond == lastLabelSecond) return
        lastLabelSecond = currentSecond
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = battery?.getIntExtra("level", 0) ?: 0
        val scale = battery?.getIntExtra("scale", 0) ?: 0
        val percent = if (scale > 0) level * 100 / scale else 0
        val status = battery?.getIntExtra("status", -1) ?: -1
        val charging = status == 2 || status == 5
        label = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  " +
            "$percent%  ${if (charging) "Charging" else "Not charging"}"
    }

    private fun drawCamera(stream: EncodedStream) {
        GLES20.glUseProgram(cameraProgram)
        val position = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(cameraProgram, "uTextureMatrix")
        val sampler = GLES20.glGetUniformLocation(cameraProgram, "uTexture")
        val sourceAspect = cameraWidth.toFloat() / cameraHeight
        val outputAspect = stream.width.toFloat() / stream.height
        val halfWidth = if (sourceAspect > outputAspect) 1f else sourceAspect / outputAspect
        val halfHeight = if (sourceAspect > outputAspect) outputAspect / sourceAspect else 1f
        cameraPositionBuffer.clear()
        cameraPositionBuffer.put(-halfWidth)
        cameraPositionBuffer.put(-halfHeight)
        cameraPositionBuffer.put(halfWidth)
        cameraPositionBuffer.put(-halfHeight)
        cameraPositionBuffer.put(-halfWidth)
        cameraPositionBuffer.put(halfHeight)
        cameraPositionBuffer.put(halfWidth)
        cameraPositionBuffer.put(halfHeight)
        cameraPositionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            cameraPositionBuffer
        )
        fullscreenTextureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(
            texCoord,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            fullscreenTextureBuffer
        )
        GLES20.glUniformMatrix4fv(matrix, 1, false, RAW_BUFFER_TEXTURE_MATRIX, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun drawLabel(output: Output) {
        if (output.labelText != label) {
            if (output.labelTexture == 0) {
                output.labelTexture = createTexture()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, output.labelTexture)
                setTextureParameters(GLES20.GL_TEXTURE_2D)
            }
            val bitmap = createLabelBitmap(label, output.stream.height)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, output.labelTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            output.labelWidth = bitmap.width
            output.labelHeight = bitmap.height
            bitmap.recycle()
            output.labelText = label
        }

        val margin = output.stream.height * LABEL_MARGIN_RATIO
        val left = -1f + 2f * margin / output.stream.width
        val top = 1f - 2f * margin / output.stream.height
        val right = left + 2f * output.labelWidth / output.stream.width
        val bottom = top - 2f * output.labelHeight / output.stream.height

        GLES20.glUseProgram(labelProgram)
        val position = GLES20.glGetAttribLocation(labelProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(labelProgram, "aTexCoord")
        val sampler = GLES20.glGetUniformLocation(labelProgram, "uTexture")
        labelPositionBuffer.clear()
        labelPositionBuffer.put(left)
        labelPositionBuffer.put(bottom)
        labelPositionBuffer.put(right)
        labelPositionBuffer.put(bottom)
        labelPositionBuffer.put(left)
        labelPositionBuffer.put(top)
        labelPositionBuffer.put(right)
        labelPositionBuffer.put(top)
        labelPositionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            labelPositionBuffer
        )
        labelTextureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(
            texCoord,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            labelTextureBuffer
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, output.labelTexture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun createLabelBitmap(text: String, streamHeight: Int): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(12f, streamHeight * LABEL_TEXT_HEIGHT_RATIO)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        val horizontalPadding = max(6, (streamHeight * LABEL_HORIZONTAL_PADDING_RATIO).toInt())
        val verticalPadding = max(4, (streamHeight * LABEL_VERTICAL_PADDING_RATIO).toInt())
        val textWidth = textPaint.measureText(text)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val bitmap = Bitmap.createBitmap(
            (textWidth + horizontalPadding * 2).toInt().coerceAtLeast(1),
            (textHeight + verticalPadding * 2).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint().apply { color = 0xAA000000.toInt() }
        canvas.drawRect(
            0f,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            backgroundPaint
        )
        canvas.drawText(
            text,
            horizontalPadding.toFloat(),
            verticalPadding - fontMetrics.ascent,
            textPaint
        )
        return bitmap
    }

    private fun createExternalTexture(): Int {
        val texture = createTexture()
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        setTextureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return texture
    }

    private fun createTexture(): Int = IntArray(1).also {
        GLES20.glGenTextures(1, it, 0)
        check(it[0] != 0) { "Unable to allocate an OpenGL texture." }
    }[0]

    private fun setTextureParameters(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "Unable to create an OpenGL shader program." }
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) {
            "Unable to link an OpenGL shader program: ${GLES20.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create an OpenGL shader." }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Unable to compile an OpenGL shader: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun destroyOutput(output: Output) {
        if (output.labelTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(output.labelTexture), 0)
        }
        EGL14.eglDestroySurface(display, output.eglSurface)
    }

    private fun makeCurrent(surface: android.opengl.EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
            "Unable to activate the OpenGL output surface."
        }
    }

    private fun runOnGlThreadAndWait(action: () -> Unit) {
        if (Looper.myLooper() == glThread.looper) {
            action()
            return
        }
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        check(glHandler.post {
            try {
                action()
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                completed.countDown()
            }
        }) { "The OpenGL thread is not accepting work." }
        check(completed.await(5, TimeUnit.SECONDS)) { "The OpenGL thread did not respond." }
        error.get()?.let { throw IllegalStateException("OpenGL operation failed.", it) }
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    companion object {
        private const val TAG = "PhoneRtspStreamer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val LABEL_TEXT_HEIGHT_RATIO = 0.045f
        private const val LABEL_MARGIN_RATIO = 0.025f
        private const val LABEL_HORIZONTAL_PADDING_RATIO = 0.025f
        private const val LABEL_VERTICAL_PADDING_RATIO = 0.012f
        private val FULLSCREEN_TEX_COORDS =
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        private val RAW_BUFFER_TEXTURE_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )
        private val LABEL_TEX_COORDS =
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val LABEL_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val LABEL_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
