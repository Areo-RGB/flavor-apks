package com.paul.sprintsync.sensor_native

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

internal class GlLumaExtractor(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val onFrameConsumed: (Long) -> Unit,
    private val emitError: (String) -> Unit,
) {
    data class LumaReadbackResult(
        val timestampNanos: Long,
        val luma: ByteArray,
        val sampleCount: Int,
        val width: Int,
        val height: Int,
    )

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var oesTextureId = 0
    private var framebufferId = 0
    private var framebufferTextureId = 0
    private var lumaProgram = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var transformLocation = -1
    private var roiStartLocation = -1
    private var roiWidthLocation = -1
    private var textureLocation = -1

    private var usingRedReadback = true
    private var readbackFormat = GLES30.GL_RED
    private var bytesPerPixel = 1
    private var maxSampleCount = 0
    private var readbackBuffer: ByteBuffer? = null
    private var glReadbackBytes = ByteArray(0)
    private var analysisBytes = ByteArray(0)
    private val textureTransform = FloatArray(16)
    private var lastTimestampNanos = 0L

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    // x, y, s, t
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    @Volatile
    private var started = false

    @Volatile
    private var released = false

    fun start() {
        if (started) {
            return
        }
        val thread = HandlerThread("GlLumaThread")
        thread.start()
        val handler = Handler(thread.looper)
        val readyLatch = CountDownLatch(1)
        var initError: Throwable? = null
        handler.post {
            try {
                initOnGlThread()
                started = true
            } catch (error: Throwable) {
                initError = error
                emitError("HS GL init failed: ${error.localizedMessage ?: "unknown"}")
            } finally {
                readyLatch.countDown()
            }
        }
        if (!readyLatch.await(4, TimeUnit.SECONDS)) {
            thread.quitSafely()
            throw IllegalStateException("Timed out starting GlLumaExtractor.")
        }
        if (initError != null) {
            thread.quitSafely()
            throw IllegalStateException(initError?.localizedMessage ?: "Failed to initialize GL extractor.")
        }
        glThread = thread
        glHandler = handler
    }

    fun getSurface(): Surface {
        return surface ?: throw IllegalStateException("GlLumaExtractor is not initialized.")
    }

    fun requestReadback(
        roiCenterX: Double,
        roiWidth: Double,
        callback: (LumaReadbackResult?) -> Unit,
    ) {
        val handler = glHandler
        if (!started || released || handler == null) {
            callback(null)
            return
        }
        handler.post {
            callback(readbackOnGlThread(roiCenterX = roiCenterX, roiWidth = roiWidth))
        }
    }

    fun release() {
        if (!started || released) {
            return
        }
        released = true
        val handler = glHandler
        val thread = glThread
        val doneLatch = CountDownLatch(1)
        handler?.post {
            try {
                releaseOnGlThread()
            } finally {
                doneLatch.countDown()
            }
        } ?: doneLatch.countDown()
        doneLatch.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        glThread = null
        glHandler = null
        started = false
    }

    private fun initOnGlThread() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display." }
        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL." }

        val config = chooseEglConfig()
        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0,
        )
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context." }

        val pbufferAttributes = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE,
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttributes, 0)
        require(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL pbuffer surface." }
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Unable to make EGL context current."
        }

        oesTextureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
            setOnFrameAvailableListener(
                {
                    onSurfaceFrameAvailable()
                },
                glHandler,
            )
        }
        surface = Surface(surfaceTexture)

        createFramebuffer()
        createLumaProgram()

        maxSampleCount = ((frameWidth + 1) / 2) * ((frameHeight + 1) / 2)
        val maxReadbackBytes = maxSampleCount * bytesPerPixel
        readbackBuffer = ByteBuffer.allocateDirect(maxReadbackBytes).order(ByteOrder.nativeOrder())
        glReadbackBytes = ByteArray(maxReadbackBytes)
        analysisBytes = ByteArray(maxSampleCount)
    }

    private fun chooseEglConfig(): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, configs.size, numConfigs, 0)) {
            "Unable to choose EGL config."
        }
        return configs[0] ?: throw IllegalStateException("No EGL config returned.")
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun createFramebuffer() {
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        framebufferTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, framebufferTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            frameWidth,
            frameHeight,
            0,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            framebufferTextureId,
            0,
        )
        var status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            usingRedReadback = false
            readbackFormat = GLES30.GL_RGBA
            bytesPerPixel = 4
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                frameWidth,
                frameHeight,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            require(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Failed to create framebuffer for HS luma extraction."
            }
        } else {
            usingRedReadback = true
            readbackFormat = GLES30.GL_RED
            bytesPerPixel = 1
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createLumaProgram() {
        val vertexShader = compileShader(
            GLES30.GL_VERTEX_SHADER,
            """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uTexTransform;
            uniform float uRoiStartX;
            uniform float uRoiWidth;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vec2 roiCoord = vec2(uRoiStartX + (aTexCoord.x * uRoiWidth), aTexCoord.y);
                vTexCoord = (uTexTransform * vec4(roiCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent(),
        )
        val fragmentShader = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            out vec4 fragColor;
            void main() {
                vec3 rgb = texture(uTexture, vTexCoord).rgb;
                float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
                fragColor = vec4(luma, 0.0, 0.0, 1.0);
            }
            """.trimIndent(),
        )
        lumaProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(lumaProgram, vertexShader)
        GLES30.glAttachShader(lumaProgram, fragmentShader)
        GLES30.glLinkProgram(lumaProgram)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(lumaProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
        require(linkStatus[0] == GLES30.GL_TRUE) {
            GLES30.glGetProgramInfoLog(lumaProgram)
        }
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        positionLocation = 0
        texCoordLocation = 1
        transformLocation = GLES30.glGetUniformLocation(lumaProgram, "uTexTransform")
        roiStartLocation = GLES30.glGetUniformLocation(lumaProgram, "uRoiStartX")
        roiWidthLocation = GLES30.glGetUniformLocation(lumaProgram, "uRoiWidth")
        textureLocation = GLES30.glGetUniformLocation(lumaProgram, "uTexture")
    }

    private fun onSurfaceFrameAvailable() {
        if (!started || released) {
            return
        }
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(textureTransform)
            lastTimestampNanos = st.timestamp
            onFrameConsumed(lastTimestampNanos)
        } catch (error: Exception) {
            emitError("HS frame consume failed: ${error.localizedMessage ?: "unknown"}")
        }
    }

    private fun readbackOnGlThread(roiCenterX: Double, roiWidth: Double): LumaReadbackResult? {
        if (!started || released) {
            return null
        }

        val safeRoiWidth = roiWidth.coerceIn(0.01, 1.0)
        val roiCenterPx = (roiCenterX * frameWidth).toInt()
        val roiWidthPx = max(1, (safeRoiWidth * frameWidth).toInt())
        val startX = max(0, min(frameWidth - 1, roiCenterPx - (roiWidthPx / 2)))
        val endX = min(frameWidth, startX + roiWidthPx)
        if (endX <= startX) {
            return null
        }

        val xStep = 2
        val yStep = 2
        val sampleWidth = ((endX - startX) + (xStep - 1)) / xStep
        val sampleHeight = (frameHeight + (yStep - 1)) / yStep
        val sampleCount = sampleWidth * sampleHeight
        if (sampleCount <= 0) {
            return null
        }

        val roiStartNormalized = startX.toFloat() / frameWidth.toFloat()
        val roiWidthNormalized = (endX - startX).toFloat() / frameWidth.toFloat()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, sampleWidth, sampleHeight)
        GLES30.glUseProgram(lumaProgram)
        GLES30.glUniformMatrix4fv(transformLocation, 1, false, textureTransform, 0)
        GLES30.glUniform1f(roiStartLocation, roiStartNormalized)
        GLES30.glUniform1f(roiWidthLocation, roiWidthNormalized)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(textureLocation, 0)
        quadVertices.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        quadVertices.position(2)
        GLES30.glEnableVertexAttribArray(texCoordLocation)
        GLES30.glVertexAttribPointer(texCoordLocation, 2, GLES30.GL_FLOAT, false, 16, quadVertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val destinationBuffer = readbackBuffer ?: return null
        destinationBuffer.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(
            0,
            0,
            sampleWidth,
            sampleHeight,
            readbackFormat,
            GLES30.GL_UNSIGNED_BYTE,
            destinationBuffer,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val bytesToCopy = sampleCount * bytesPerPixel
        destinationBuffer.position(0)
        destinationBuffer.get(glReadbackBytes, 0, bytesToCopy)

        if (usingRedReadback) {
            System.arraycopy(glReadbackBytes, 0, analysisBytes, 0, sampleCount)
        } else {
            for (i in 0 until sampleCount) {
                analysisBytes[i] = glReadbackBytes[i * 4]
            }
        }

        return LumaReadbackResult(
            timestampNanos = lastTimestampNanos,
            luma = analysisBytes.copyOf(sampleCount),
            sampleCount = sampleCount,
            width = sampleWidth,
            height = sampleHeight,
        )
    }

    private fun releaseOnGlThread() {
        try {
            surfaceTexture?.setOnFrameAvailableListener(null as SurfaceTexture.OnFrameAvailableListener?)
        } catch (_: Exception) {
            // ignored
        }
        surfaceTexture?.release()
        surfaceTexture = null
        surface?.release()
        surface = null

        if (lumaProgram != 0) {
            GLES30.glDeleteProgram(lumaProgram)
            lumaProgram = 0
        }
        if (framebufferTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(framebufferTextureId), 0)
            framebufferTextureId = 0
        }
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        if (oesTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        require(status[0] == GLES30.GL_TRUE) {
            GLES30.glGetShaderInfoLog(shader)
        }
        return shader
    }
}
