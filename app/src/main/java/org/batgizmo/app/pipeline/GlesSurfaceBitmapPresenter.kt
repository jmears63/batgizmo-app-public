/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.batgizmo.app.pipeline

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import android.view.SurfaceHolder
import org.batgizmo.app.HORange
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GLES **2.0-only** spectrogram blit onto a [SurfaceView] [Surface].
 *
 * Uses [android.opengl.GLES20] exclusively (no GLES 3.x APIs). The EGL context is
 * requested as OpenGL ES 2 (`EGL_OPENGL_ES2_BIT` + `EGL_CONTEXT_CLIENT_VERSION` 2).
 *
 * The resident texture holds only the current visible bitmap window ([src]):
 * - Pan/zoom ([src] changes) → full visible-window upload
 * - Same [src] + dirty time columns → upload only `dirty ∩ src` strips
 * - Neither → redraw with UVs 0..1
 * - If [src] exceeds [GL_MAX_TEXTURE_SIZE], the window is downsampled to fit
 *   (aspect preserved); dirty updates then re-upload the whole scaled window.
 *
 * Vertices go through a VBO — Adreno has crashed on client-side attrib arrays here.
 *
 * Enable with [SpectrogramSurfacePresenters.USE_GLES].
 */
class GlesSurfaceBitmapPresenter : SurfaceBitmapPresenter {

    @Volatile
    private var viewportWidth: Int = 0

    @Volatile
    private var viewportHeight: Int = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null

    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uViewportLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var textureId: Int = 0
    private var textureWidth: Int = 0
    private var textureHeight: Int = 0
    /** Bitmap region currently represented by the resident texture. */
    private val textureSrc = Rect()
    /** False until at least one successful upload into the current texture. */
    private var textureHasContent: Boolean = false
    /** True when the resident texture is a downscaled copy of [textureSrc]. */
    private var textureDownsampled: Boolean = false
    private var vboId: Int = 0
    /** 1×1 yellow RGB_565 texture for the amplitude cursor overlay. */
    private var cursorTextureId: Int = 0

    /** Scratch for tightly packed RGB_565 uploads (draw-thread only). */
    private var pixelScratch: ByteBuffer? = null

    /** Reused buffer for [Bitmap.copyPixelsToBuffer] (may include row stride). */
    private var strideScratch: ByteBuffer? = null

    /** [GLES20.GL_MAX_TEXTURE_SIZE] after context create; 0 until queried. */
    private var maxTextureSize: Int = 0

    private val quadScratch: FloatBuffer =
        ByteBuffer.allocateDirect(QUAD_FLOATS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private val sync = Any()

    /** Scratch for clipping [src] / dirty intersections (draw-thread only). */
    private val clipScratch = Rect()
    private val dirtyIntersectScratch = Rect()
    private val cursorDstScratch = Rect()

    override fun onSurfaceCreated(holder: SurfaceHolder) {
        // EGL is created on first [present] (draw thread).
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
        synchronized(sync) {
            releaseEglLocked()
        }
    }

    override fun present(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
        src: Rect,
        dst: Rect,
        paint: Paint,
        dirtyColumns: HORange?,
        cursorX: Float?,
    ) {
        synchronized(sync) {
            if (!ensureEglLocked(holder))
                return

            val w = if (viewportWidth > 0) viewportWidth else holder.surfaceFrame.width()
            val h = if (viewportHeight > 0) viewportHeight else holder.surfaceFrame.height()
            if (w <= 0 || h <= 0)
                return

            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (bitmap != null && !bitmap.isRecycled &&
                bitmap.width > 0 && bitmap.height > 0 &&
                src.width() > 0 && src.height() > 0 &&
                dst.width() > 0 && dst.height() > 0
            ) {
                // On failure leave the cleared black frame; always swap below.
                if (ensureProgramLocked() &&
                    syncTextureLocked(bitmap, src, dirtyColumns) &&
                    textureHasContent
                ) {
                    drawQuadLocked(dst, w, h, textureId)
                }
            }

            if (cursorX != null && ensureProgramLocked() && ensureCursorTextureLocked()) {
                val half = CURSOR_WIDTH_PX * 0.5f
                cursorDstScratch.set(
                    (cursorX - half).toInt(),
                    0,
                    (cursorX + half).toInt().coerceAtLeast((cursorX - half).toInt() + 1),
                    h,
                )
                drawQuadLocked(cursorDstScratch, w, h, cursorTextureId)
            }

            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Timber.e(
                    "GlesSurfaceBitmapPresenter: eglSwapBuffers failed: 0x${
                        Integer.toHexString(EGL14.eglGetError())
                    }"
                )
            }
        }
    }

    private fun ensureEglLocked(holder: SurfaceHolder): Boolean {
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            releaseEglLocked()
            return false
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglSurface != EGL14.EGL_NO_SURFACE &&
            windowSurface === surface
        ) {
            if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                return true
            Timber.w("GlesSurfaceBitmapPresenter: eglMakeCurrent failed; recreating")
            releaseEglLocked()
        } else if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            releaseEglLocked()
        }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Timber.e("GlesSurfaceBitmapPresenter: eglGetDisplay failed")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Timber.e("GlesSurfaceBitmapPresenter: eglInitialize failed")
            return false
        }

        // GLES 2.0 only — do not request ES 3 configs/contexts.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0 || configs[0] == null
        ) {
            Timber.e("GlesSurfaceBitmapPresenter: eglChooseConfig failed")
            EGL14.eglTerminate(display)
            return false
        }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_MAJOR_VERSION,
            EGL14.EGL_NONE,
        )
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Timber.e("GlesSurfaceBitmapPresenter: eglCreateContext failed")
            EGL14.eglTerminate(display)
            return false
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val window = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
        if (window == null || window == EGL14.EGL_NO_SURFACE) {
            Timber.e("GlesSurfaceBitmapPresenter: eglCreateWindowSurface failed")
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }

        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            Timber.e("GlesSurfaceBitmapPresenter: eglMakeCurrent failed on create")
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }

        eglDisplay = display
        eglContext = context
        eglSurface = window
        windowSurface = surface
        program = 0
        textureId = 0
        textureWidth = 0
        textureHeight = 0
        textureSrc.setEmpty()
        textureHasContent = false
        textureDownsampled = false
        vboId = 0
        cursorTextureId = 0

        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        val gpuMax = maxTex[0]
        val cap = SpectrogramSurfacePresenters.DEBUG_MAX_TEXTURE_SIZE_CAP
        maxTextureSize = if (cap > 0) minOf(gpuMax, cap) else gpuMax

        val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        if (cap > 0 && maxTextureSize < gpuMax) {
            Timber.i(
                "GPU GL_MAX_TEXTURE_SIZE=$gpuMax, using debug cap=$maxTextureSize " +
                    "(GL_VERSION=$glVersion, ES $GLES_MAJOR_VERSION.0 API)"
            )
        } else {
            Timber.i(
                "GPU GL_MAX_TEXTURE_SIZE=$maxTextureSize " +
                    "(GL_VERSION=$glVersion, ES $GLES_MAJOR_VERSION.0 API)"
            )
        }
        return true
    }

    private fun ensureProgramLocked(): Boolean {
        if (program != 0)
            return true

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
            return false
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Timber.e(
                "GlesSurfaceBitmapPresenter: program link failed: ${
                    GLES20.glGetProgramInfoLog(prog)
                }"
            )
            GLES20.glDeleteProgram(prog)
            return false
        }
        program = prog
        aPositionLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        uViewportLoc = GLES20.glGetUniformLocation(prog, "uViewport")
        uTextureLoc = GLES20.glGetUniformLocation(prog, "uTexture")
        if (aPositionLoc < 0 || aTexCoordLoc < 0 || uViewportLoc < 0 || uTextureLoc < 0) {
            Timber.e("GlesSurfaceBitmapPresenter: missing shader attrib/uniform")
            GLES20.glDeleteProgram(program)
            program = 0
            return false
        }

        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        return true
    }

    /**
     * Keep a visible-window texture for [src]:
     * - [src] changed / empty texture → full upload of [src] (downsampled if needed)
     * - same [src] + [dirtyColumns], 1:1 texture → strip upload of intersection
     * - same [src] + [dirtyColumns], downsampled texture → full scaled re-upload
     * - neither → no upload
     */
    private fun syncTextureLocked(
        bitmap: Bitmap,
        src: Rect,
        dirtyColumns: HORange?,
    ): Boolean {
        if (bitmap.config != Bitmap.Config.RGB_565) {
            Timber.e("GlesSurfaceBitmapPresenter: expected RGB_565, got ${bitmap.config}")
            return false
        }

        val bw = bitmap.width
        val bh = bitmap.height
        clipScratch.set(
            src.left.coerceIn(0, bw),
            src.top.coerceIn(0, bh),
            src.right.coerceIn(0, bw),
            src.bottom.coerceIn(0, bh),
        )
        val sw = clipScratch.width()
        val sh = clipScratch.height()
        if (sw <= 0 || sh <= 0)
            return false

        val (texW, texH) = textureSizeForSrc(sw, sh)
        val downsampled = texW != sw || texH != sh

        val srcChanged = !textureHasContent || textureSrc != clipScratch
        if (srcChanged) {
            if (!ensureTextureStorageLocked(texW, texH))
                return false
            if (!uploadSrcWindowLocked(bitmap, clipScratch, texW, texH))
                return false
            textureSrc.set(clipScratch)
            textureDownsampled = downsampled
            return true
        }

        if (dirtyColumns == null || dirtyColumns.start >= dirtyColumns.exclusiveEnd)
            return textureHasContent

        val dirtyLeft = dirtyColumns.start.coerceIn(0, bw)
        val dirtyRight = dirtyColumns.exclusiveEnd.coerceIn(0, bw)
        if (dirtyLeft >= dirtyRight)
            return textureHasContent

        dirtyIntersectScratch.set(
            maxOf(dirtyLeft, textureSrc.left),
            textureSrc.top,
            minOf(dirtyRight, textureSrc.right),
            textureSrc.bottom,
        )
        if (dirtyIntersectScratch.isEmpty)
            return textureHasContent

        // Scaled texture is not 1:1 with bitmap columns — refresh the whole window.
        if (textureDownsampled) {
            if (!ensureTextureStorageLocked(texW, texH))
                return false
            return uploadSrcWindowLocked(bitmap, textureSrc, texW, texH)
        }

        val stripW = dirtyIntersectScratch.width()
        val stripH = dirtyIntersectScratch.height()
        return uploadRegionLocked(
            bitmap,
            dirtyIntersectScratch.left,
            dirtyIntersectScratch.top,
            stripW,
            stripH,
            destX = dirtyIntersectScratch.left - textureSrc.left,
            destY = dirtyIntersectScratch.top - textureSrc.top,
        )
    }

    /**
     * Texture dimensions for a visible window of [sw]×[sh], capped to
     * [maxTextureSize] while preserving aspect ratio.
     */
    private fun textureSizeForSrc(sw: Int, sh: Int): Pair<Int, Int> {
        if (maxTextureSize <= 0 || (sw <= maxTextureSize && sh <= maxTextureSize))
            return Pair(sw, sh)
        val scale = minOf(
            maxTextureSize.toFloat() / sw.toFloat(),
            maxTextureSize.toFloat() / sh.toFloat(),
        )
        val texW = (sw * scale).toInt().coerceIn(1, maxTextureSize)
        val texH = (sh * scale).toInt().coerceIn(1, maxTextureSize)
        return Pair(texW, texH)
    }

    /**
     * Upload the full [src] window into the resident texture at 1:1 or downsampled
     * to [texW]×[texH].
     */
    private fun uploadSrcWindowLocked(
        bitmap: Bitmap,
        src: Rect,
        texW: Int,
        texH: Int,
    ): Boolean {
        val sw = src.width()
        val sh = src.height()
        if (sw == texW && sh == texH) {
            return uploadRegionLocked(bitmap, src.left, src.top, sw, sh, 0, 0)
        }

        Timber.i(
            "GlesSurfaceBitmapPresenter: resampling bitmap " +
                "${bitmap.width}x${bitmap.height} src=${src.toShortString()} " +
                "(${sw}x$sh) → texture ${texW}x$texH " +
                "(maxTextureSize=$maxTextureSize)"
        )

        val region = Bitmap.createBitmap(bitmap, src.left, src.top, sw, sh)
        var scaled: Bitmap? = null
        var rgb565: Bitmap? = null
        try {
            scaled = Bitmap.createScaledBitmap(region, texW, texH, true)
            rgb565 = if (scaled.config == Bitmap.Config.RGB_565) {
                scaled
            } else {
                scaled.copy(Bitmap.Config.RGB_565, false)
            }
            if (rgb565 == null) {
                Timber.e("GlesSurfaceBitmapPresenter: RGB_565 copy of scaled bitmap failed")
                return false
            }
            return uploadRegionLocked(rgb565, 0, 0, texW, texH, 0, 0)
        } finally {
            region.recycle()
            val s = scaled
            val r = rgb565
            if (s != null && s !== r) s.recycle()
            if (r != null && r !== region) r.recycle()
        }
    }

    private fun ensureTextureStorageLocked(tw: Int, th: Int): Boolean {
        if (textureId != 0 && textureWidth == tw && textureHeight == th)
            return true

        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            if (textureId == 0) {
                Timber.e("GlesSurfaceBitmapPresenter: glGenTextures failed")
                return false
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGB,
            tw,
            th,
            0,
            GLES20.GL_RGB,
            GLES20.GL_UNSIGNED_SHORT_5_6_5,
            null,
        )
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Timber.e(
                "GlesSurfaceBitmapPresenter: glTexImage2D alloc error 0x${
                    Integer.toHexString(err)
                }"
            )
            textureWidth = 0
            textureHeight = 0
            textureSrc.setEmpty()
            textureHasContent = false
            textureDownsampled = false
            return false
        }
        textureWidth = tw
        textureHeight = th
        textureSrc.setEmpty()
        textureHasContent = false
        textureDownsampled = false
        return true
    }

    /**
     * Pack a bitmap region and copy it into the resident texture at ([destX],[destY]).
     */
    private fun uploadRegionLocked(
        bitmap: Bitmap,
        srcLeft: Int,
        srcTop: Int,
        width: Int,
        height: Int,
        destX: Int,
        destY: Int,
    ): Boolean {
        if (width <= 0 || height <= 0)
            return false

        val packed = packRgb565Region(bitmap, srcLeft, srcTop, width, height) ?: return false

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            destX,
            destY,
            width,
            height,
            GLES20.GL_RGB,
            GLES20.GL_UNSIGNED_SHORT_5_6_5,
            packed,
        )
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Timber.e(
                "GlesSurfaceBitmapPresenter: glTexSubImage2D error 0x${
                    Integer.toHexString(err)
                }"
            )
            return false
        }
        textureHasContent = true
        return true
    }

    /** Copy [w]×[h] at ([left],[top]) into a tightly packed direct RGB_565 buffer. */
    private fun packRgb565Region(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
    ): ByteBuffer? {
        val packedBytes = w * h * 2
        var packed = pixelScratch
        if (packed == null || packed.capacity() < packedBytes) {
            packed = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
            pixelScratch = packed
        }
        packed.clear()

        // NDK AndroidBitmap_lockPixels + row memcpy was tried instead of this
        // createBitmap/copyPixelsToBuffer path; profiles showed only ~2% process
        // CPU and ~20% of (GLES draw − eglSwap) improvement — not worth the JNI.
        val region = Bitmap.createBitmap(bitmap, left, top, w, h)
        try {
            val rowBytes = region.rowBytes
            val strideBytes = rowBytes * h
            var strideBuf = strideScratch
            if (strideBuf == null || strideBuf.capacity() < strideBytes) {
                strideBuf = ByteBuffer.allocateDirect(strideBytes).order(ByteOrder.nativeOrder())
                strideScratch = strideBuf
            }
            strideBuf.clear()
            strideBuf.limit(strideBytes)
            region.copyPixelsToBuffer(strideBuf)
            strideBuf.rewind()

            val srcShorts: ShortBuffer = strideBuf.asShortBuffer()
            val dstShorts: ShortBuffer = packed.asShortBuffer()
            val rowShorts = rowBytes / 2
            if (rowShorts == w) {
                dstShorts.put(srcShorts)
            } else {
                for (y in 0 until h) {
                    srcShorts.position(y * rowShorts)
                    srcShorts.limit(y * rowShorts + w)
                    dstShorts.put(srcShorts)
                }
                srcShorts.clear()
            }
        } finally {
            region.recycle()
        }
        packed.position(0)
        packed.limit(packedBytes)
        return packed
    }

    /** Draw [dst] sampling [texId] with UVs 0..1. */
    private fun drawQuadLocked(
        dst: Rect,
        viewportW: Int,
        viewportH: Int,
        texId: Int,
    ) {
        val u0 = 0f
        val u1 = 1f
        val v0 = 0f
        val v1 = 1f

        quadScratch.position(0)
        quadScratch.put(dst.left.toFloat()).put(dst.top.toFloat()).put(u0).put(v0)
        quadScratch.put(dst.right.toFloat()).put(dst.top.toFloat()).put(u1).put(v0)
        quadScratch.put(dst.left.toFloat()).put(dst.bottom.toFloat()).put(u0).put(v1)
        quadScratch.put(dst.right.toFloat()).put(dst.bottom.toFloat()).put(u1).put(v1)
        quadScratch.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            QUAD_FLOATS * 4,
            quadScratch,
            GLES20.GL_DYNAMIC_DRAW,
        )

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uViewportLoc, viewportW.toFloat(), viewportH.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTextureLoc, 0)

        val stride = 4 * 4
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 2, GLES20.GL_FLOAT, false, stride, 0
        )
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc, 2, GLES20.GL_FLOAT, false, stride, 2 * 4
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Ensure a 1×1 yellow RGB_565 texture exists for the cursor overlay. */
    private fun ensureCursorTextureLocked(): Boolean {
        if (cursorTextureId != 0)
            return true
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        cursorTextureId = ids[0]
        if (cursorTextureId == 0)
            return false
        val pixel = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
        pixel.putShort(CURSOR_RGB565)
        pixel.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTextureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGB,
            1,
            1,
            0,
            GLES20.GL_RGB,
            GLES20.GL_UNSIGNED_SHORT_5_6_5,
            pixel,
        )
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun releaseEglLocked() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
            return

        val madeCurrent =
            eglSurface != EGL14.EGL_NO_SURFACE &&
                eglContext != EGL14.EGL_NO_CONTEXT &&
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        if (madeCurrent) {
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (cursorTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(cursorTextureId), 0)
                cursorTextureId = 0
            }
            if (vboId != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
                vboId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            textureWidth = 0
            textureHeight = 0
            textureSrc.setEmpty()
            textureHasContent = false
            textureDownsampled = false
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        } else {
            textureId = 0
            cursorTextureId = 0
            vboId = 0
            program = 0
            textureWidth = 0
            textureHeight = 0
            textureSrc.setEmpty()
            textureHasContent = false
            textureDownsampled = false
        }

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        windowSurface = null
        pixelScratch = null
        strideScratch = null
        maxTextureSize = 0
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Timber.e(
                "GlesSurfaceBitmapPresenter: shader compile failed: ${
                    GLES20.glGetShaderInfoLog(shader)
                }"
            )
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /** Hard cap: OpenGL ES 2.0 only — never request or call ES 3.x. */
        private const val GLES_MAJOR_VERSION = 2

        private const val QUAD_FLOATS = 4 * 4 // 4 verts × (x,y,u,v)

        private const val CURSOR_WIDTH_PX = 2
        /** RGB_565 yellow (#FFFF00). */
        private const val CURSOR_RGB565: Short = 0xFFE0.toShort()

        // No leading blank line — some drivers are strict about #version placement.
        private const val VERTEX_SHADER =
            "#version 100\n" +
                "attribute vec2 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "uniform vec2 uViewport;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  float x = aPosition.x / uViewport.x * 2.0 - 1.0;\n" +
                "  float y = 1.0 - aPosition.y / uViewport.y * 2.0;\n" +
                "  gl_Position = vec4(x, y, 0.0, 1.0);\n" +
                "  vTexCoord = aTexCoord;\n" +
                "}\n"

        private const val FRAGMENT_SHADER =
            "#version 100\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n"
    }
}
