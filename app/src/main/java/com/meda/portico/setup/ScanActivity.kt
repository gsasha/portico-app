package com.meda.portico.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.R
import com.meda.portico.core.scan.LumaPlane
import com.meda.portico.core.scan.QrFrameDecoder
import com.meda.portico.core.scan.ScanHandoff
import com.meda.portico.core.scan.Viewfinder

/**
 * The enrollment QR scanner (P2 plan §6 PR-C2): a full-screen viewfinder with an aiming square,
 * Camera2 straight into ZXing core, as the old intercom app's `TransferActivity` (proved on real
 * phones 2026-09-13). No CameraX, no Play services, no other library.
 *
 * - The first code read is put in [ScanHandoff] (in process, never an Intent extra), the result
 *   is RESULT_OK and the screen closes. Setup parses it with the paste field's code. This screen
 *   never looks at, stores or logs the text.
 * - FLAG_SECURE: the viewfinder shows a code that holds a token.
 * - The camera is opened on resume and released on pause.
 * - No camera, or a camera that fails: the screen closes with [RESULT_NO_CAMERA] or
 *   [RESULT_CAMERA_FAILED] and Setup says so next to the paste field.
 * - The CAMERA permission is asked by Setup before this screen opens. Revoked in between, the
 *   screen closes with [RESULT_CAMERA_FAILED].
 *
 * Portrait only (manifest): the preview transform is scale-only, because TextureView already
 * compensates for the sensor's orientation and the locked activity pins the device's.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var status: TextView

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var frameW = 0
    private var frameH = 0
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var resumed = false

    /** One result per visit: set on the camera thread when a code is read, then frames are ignored. */
    @Volatile private var decoded = false
    private val decoder = QrFrameDecoder() // used on the camera thread only

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_scan)
        preview = findViewById(R.id.scan_preview)
        status = findViewById(R.id.scan_status)
        findViewById<Button>(R.id.scan_cancel).setOnClickListener { finishWith(RESULT_CANCELED) }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            finishWith(RESULT_NO_CAMERA)
            return
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (resumed) openCamera()
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = applyTransform(w, h)

            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (camera == null && preview.isAvailable && !decoded) openCamera()
    }

    override fun onPause() {
        resumed = false
        closeCamera() // a camera held in the background is one nobody else can use
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    private fun finishWith(code: Int) {
        setResult(code)
        finish()
    }

    @SuppressLint("MissingPermission") // checked on the line above the call
    private fun openCamera() {
        if (isFinishing || camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWith(RESULT_CAMERA_FAILED)
            return
        }
        val cm = getSystemService(CameraManager::class.java)
        try {
            val id = backCamera(cm) ?: return finishWith(RESULT_NO_CAMERA)
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }.orEmpty()
            val (w, h) = Viewfinder.chooseFrameSize(sizes)
            frameW = w
            frameH = h
            startCameraThread()
            val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener(::onFrame, cameraHandler)
            reader = r
            // The scale needs the frame size, and onSurfaceTextureSizeChanged may not fire again.
            applyTransform(preview.width, preview.height)
            cm.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        camera = d
                        startPreview()
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        if (camera === d) camera = null
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        if (camera === d) camera = null
                        runOnUiThread { finishWith(RESULT_CAMERA_FAILED) }
                    }
                },
                cameraHandler,
            )
            status.setText(R.string.scan_searching)
        } catch (e: CameraAccessException) {
            finishWith(RESULT_CAMERA_FAILED)
        } catch (e: SecurityException) {
            finishWith(RESULT_CAMERA_FAILED)
        } catch (e: IllegalArgumentException) {
            finishWith(RESULT_CAMERA_FAILED)
        }
    }

    /** The back camera, else the first one: a device with only a front camera can still scan. */
    private fun backCamera(cm: CameraManager): String? {
        val ids = cm.cameraIdList
        return ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
    }

    private fun startPreview() {
        val d = camera ?: return
        val r = reader ?: return
        val st = preview.surfaceTexture ?: return
        try {
            st.setDefaultBufferSize(frameW, frameH)
            val ps = Surface(st)
            val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(ps)
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            @Suppress("DEPRECATION") // the List<Surface> overload is fine down to minSdk 26
            d.createCaptureSession(
                listOf(ps, r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            s.setRepeatingRequest(rq.build(), null, cameraHandler)
                        } catch (e: CameraAccessException) {
                            runOnUiThread { finishWith(RESULT_CAMERA_FAILED) }
                        } catch (e: IllegalStateException) {
                            // Closed under us (paused): onResume opens it again.
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runOnUiThread { finishWith(RESULT_CAMERA_FAILED) }
                    }
                },
                cameraHandler,
            )
        } catch (e: CameraAccessException) {
            runOnUiThread { finishWith(RESULT_CAMERA_FAILED) }
        } catch (e: IllegalStateException) {
            // The device was closed between onOpened and here (paused): onResume reopens.
        }
    }

    /** One frame, on the camera thread. A frame with no code is not an error: the next is ~33 ms away. */
    private fun onFrame(r: ImageReader) {
        val img = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            if (decoded) return
            val plane = img.planes[0]
            val luma = LumaPlane.compact(plane.buffer, plane.rowStride, plane.pixelStride, img.width, img.height)
            val text = decoder.decode(luma, img.width, img.height) ?: return
            decoded = true
            ScanHandoff.put(text)
            runOnUiThread { finishWith(RESULT_OK) }
        } finally {
            img.close()
        }
    }

    /** Centre-crop, scale only (see the class doc), plus the upside-down portrait some devices allow. */
    private fun applyTransform(viewW: Int, viewH: Int) {
        if (frameW == 0 || viewW == 0 || viewH == 0) return
        val (sx, sy) = Viewfinder.centreCropScale(frameW, frameH, viewW, viewH)
        val m = Matrix()
        m.setScale(sx, sy, viewW / 2f, viewH / 2f)
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.rotation == Surface.ROTATION_180) m.postRotate(180f, viewW / 2f, viewH / 2f)
        preview.setTransform(m)
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        val t = HandlerThread("portico-qr-camera").also { it.start() }
        cameraThread = t
        cameraHandler = Handler(t.looper)
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: RuntimeException) {
        }
        session = null
        try {
            camera?.close()
        } catch (_: RuntimeException) {
        }
        camera = null
        try {
            reader?.close()
        } catch (_: RuntimeException) {
        }
        reader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    companion object {
        const val RESULT_NO_CAMERA = RESULT_FIRST_USER + 1
        const val RESULT_CAMERA_FAILED = RESULT_FIRST_USER + 2
    }
}

/**
 * The aiming square: four scrim rectangles around a clear square plus corner brackets, drawn
 * with [Viewfinder.aimingSquare]. Not a PorterDuff hole: that needs an offscreen layer, which
 * behaves differently across hardware layers (the old app's reasoning).
 */
class ScanReticleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val scrim = Paint().apply { color = Color.parseColor("#88000000") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a5b4fc")
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val b = Viewfinder.aimingSquare(width, height)
        cv.drawRect(0f, 0f, w, b.top, scrim)
        cv.drawRect(0f, b.bottom, w, h, scrim)
        cv.drawRect(0f, b.top, b.left, b.bottom, scrim)
        cv.drawRect(b.right, b.top, w, b.bottom, scrim)
        val arm = b.width * 0.12f
        val (l, t, r, bt) = listOf(b.left, b.top, b.right, b.bottom)
        cv.drawLine(l, t + arm, l, t, edge); cv.drawLine(l, t, l + arm, t, edge)
        cv.drawLine(r - arm, t, r, t, edge); cv.drawLine(r, t, r, t + arm, edge)
        cv.drawLine(l, bt - arm, l, bt, edge); cv.drawLine(l, bt, l + arm, bt, edge)
        cv.drawLine(r - arm, bt, r, bt, edge); cv.drawLine(r, bt, r, bt - arm, edge)
    }
}
