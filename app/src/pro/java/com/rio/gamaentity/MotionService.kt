package com.rio.gamaentity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class MotionService : LifecycleService() {
    private class Pt(val t: Long, val x: Float, val y: Float)

    private var recognizer: GestureRecognizer? = null
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var dot: View? = null
    private var dotParams: WindowManager.LayoutParams? = null
    private var screenW = 1080
    private var screenH = 2400
    @Volatile private var started = false

    private var lastFrame = 0L
    private var curX = 0.5f
    private var curY = 0.5f
    private var lastSeen = 0L
    private val trail = ArrayList<Pt>()
    private val posHist = ArrayList<Pt>()
    private var pinching = false
    private var lastClick = 0L
    private var lastSwipe = 0L
    private var twoFrames = 0
    private val waveTrail = ArrayList<Pt>()
    private var lastDir = ""
    private var anchorSet = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var pinchFrames = 0

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("motion", "Motion mode", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "motion")
            .setContentTitle("GAMA motion mode is on")
            .setContentText("Turn off in the GAMA menu")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(79, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(79, n)
        if (!started) begin()
        return START_STICKY
    }

    private fun makeDot() {
        if (dot != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics
        screenW = d.widthPixels
        screenH = d.heightPixels
        val v = View(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0x88FFB300.toInt())
        bg.setStroke(4, Color.WHITE)
        v.background = bg
        val lp = WindowManager.LayoutParams(
            64, 64,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = screenW / 2
        lp.y = screenH / 2
        v.visibility = View.GONE
        wm?.addView(v, lp)
        dot = v
        dotParams = lp
    }

    private fun showDot(px: Float, py: Float, visible: Boolean) {
        main.post {
            val v = dot ?: return@post
            val lp = dotParams ?: return@post
            lp.x = (px - 32f).toInt()
            lp.y = (py - 32f).toInt()
            v.visibility = if (visible) View.VISIBLE else View.GONE
            try { wm?.updateViewLayout(v, lp) } catch (e: Exception) {}
        }
    }

    private fun flashDot() {
        (dot?.background as? GradientDrawable)?.setColor(0xFF1A6B3C.toInt())
        main.postDelayed({ (dot?.background as? GradientDrawable)?.setColor(0x88FFB300.toInt()) }, 250)
    }

    private fun begin() {
        started = true
        try {
            val bytes = GestureModel.file(this).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            val base = BaseOptions.builder().setModelAssetBuffer(buf).build()
            val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.3f)
                .setMinHandPresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, opts)
            makeDot()
        } catch (e: Exception) {
            android.util.Log.e("GAMA_MOTION", "model failed: " + e.message)
            stopSelf()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { img -> analyze(img) }
                p.unbindAll()
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                try {
                    val range = cam.cameraInfo.exposureState.exposureCompensationRange
                    if (range.upper > 0) cam.cameraControl.setExposureCompensationIndex(maxOf(1, range.upper / 2))
                } catch (e: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("GAMA_MOTION", "camera failed: " + e.message)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrame < 30) return
            lastFrame = now
            val bmp = img.toBitmap()
            val m = Matrix()
            m.postRotate(img.imageInfo.rotationDegrees.toFloat())
            val rot = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val result = recognizer?.recognizeForVideo(BitmapImageBuilder(rot).build(), now)
            val hand = result?.landmarks()?.firstOrNull()
            if (hand == null || hand.size < 21) {
                onNoHand(now)
                return
            }
            val idx = hand[8]
            val th = hand[4]
            val wr = hand[0]
            val md = hand[9]
            val w = rot.width.toFloat()
            val h = rot.height.toFloat()
            val pdx = (idx.x() - th.x()) * w
            val pdy = (idx.y() - th.y()) * h
            val sdx = (wr.x() - md.x()) * w
            val sdy = (wr.y() - md.y()) * h
            val handSize = sqrt(sdx * sdx + sdy * sdy)
            val pdz = (idx.z() - th.z()) * w
            val pinchDist = sqrt(pdx * pdx + pdy * pdy + pdz * pdz)
            val ratio = if (handSize > 1f) pinchDist / handSize else 1f
            fun reach(a: Int, b: Int): Float {
                val x1 = (hand[a].x() - wr.x()) * w
                val y1 = (hand[a].y() - wr.y()) * h
                val x2 = (hand[b].x() - wr.x()) * w
                val y2 = (hand[b].y() - wr.y()) * h
                val d1 = sqrt(x1 * x1 + y1 * y1)
                val d2 = sqrt(x2 * x2 + y2 * y2)
                return if (d2 > 1f) d1 / d2 else 1f
            }
            val open = reach(8, 6) > 1.3f && reach(12, 10) > 1.3f && reach(16, 14) > 1.3f && reach(20, 18) > 1.3f
            val two = open || (reach(8, 6) > 1.3f && reach(12, 10) > 1.3f && reach(16, 14) < 1.15f && reach(20, 18) < 1.15f)
            onHand(1f - idx.x(), idx.y(), ratio, now, two, 1f - md.x(), md.y())
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private fun onNoHand(now: Long) {
        if (now - lastSeen > 250) { pinching = false; pinchFrames = 0; releaseFrames = 0; joyMode = false; tapPending = false; armed = false; openFrames = 0; setDot(0x88FFB300.toInt()) }
        if (now - lastSeen > 600) {
            showDot(curX * screenW, curY * screenH, false)
            trail.clear()
            pinching = false
        }
    }

    private var pinchStart = 0L
    private var joyMode = false
    private var joyAnchorX = 0f
    private var joyAnchorY = 0f
    private var lastJoy = 0L
    private var tapPending = false
    private var tapAt = 0L
    private var tapX = 0f
    private var tapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var releaseFrames = 0

    private fun setDot(color: Int) {
        main.post { (dot?.background as? GradientDrawable)?.setColor(color) }
    }

    private var armed = true
    private var openFrames = 0

    private fun onHand(rawX: Float, rawY: Float, ratio: Float, now: Long, two: Boolean, palmX: Float, palmY: Float) {
        lastSeen = now
        val was = pinching
        if (ratio > 0.40f) {
            openFrames++
            if (openFrames >= 2) armed = true
        } else {
            openFrames = 0
        }
        if (!pinching) {
            if (armed && ratio < 0.28f) pinchFrames++ else pinchFrames = 0
            if (pinchFrames >= 2) {
                pinching = true
                armed = false
                releaseFrames = 0
            }
        } else {
            val relT = if (joyMode) 0.55f else 0.42f
            if (ratio > relT) releaseFrames++ else releaseFrames = 0
            if (releaseFrames >= 3 || now - pinchStart > 8000) {
                pinching = false
                pinchFrames = 0
                releaseFrames = 0
            }
        }

        if (pinching && !was) {
            pinchStart = now
            joyMode = false
            val ref = posHist.firstOrNull { now - it.t <= 450 } ?: posHist.lastOrNull()
            downX = ref?.x ?: (curX * screenW)
            downY = ref?.y ?: (curY * screenH)
        }

        if (pinching) {
            if (!joyMode && now - pinchStart > 450) {
                joyMode = true
                tapPending = false
                joyAnchorX = palmX
                joyAnchorY = palmY
                setDot(0xFF2196F3.toInt())
            }
            if (joyMode && now - lastJoy > 450) {
                val dx = palmX - joyAnchorX
                val dy = palmY - joyAnchorY
                val ax = abs(dx)
                val ay = abs(dy)
                val big = maxOf(ax, ay)
                val small = minOf(ax, ay)
                if (big > 0.06f && small < big * 0.8f) {
                    lastJoy = now
                    val cmd = if (ax > ay) { if (dx > 0) "SCROLL:RIGHT" else "SCROLL:LEFT" } else { if (dy < 0) "SCROLL:DOWN" else "SCROLL:UP" }
                    main.post { GamaAccessibilityService.executeAction(cmd) }
                }
            }
            return
        }

        if (was) {
            setDot(0x88FFB300.toInt())
            if (!joyMode && now - pinchStart <= 450) {
                if (tapPending && now - tapAt <= 600) {
                    tapPending = false
                    val cx = tapX
                    val cy = tapY
                    main.post {
                        GamaAccessibilityService.gestureClick(cx, cy)
                        flashDot()
                    }
                } else {
                    tapPending = true
                    tapAt = now
                    tapX = downX
                    tapY = downY
                }
            }
            joyMode = false
        }
        if (tapPending && now - tapAt > 600) tapPending = false

        val tx = ((rawX - 0.15f) / 0.7f).coerceIn(0f, 1f)
        val ty = ((rawY - 0.15f) / 0.7f).coerceIn(0f, 1f)
        val gap = sqrt((tx - curX) * (tx - curX) + (ty - curY) * (ty - curY))
        val alpha = (0.35f + gap * 9f).coerceIn(0.35f, 0.95f)
        curX += (tx - curX) * alpha
        curY += (ty - curY) * alpha
        val px = curX * screenW
        val py = curY * screenH
        posHist.add(Pt(now, px, py))
        posHist.removeAll { now - it.t > 600 }
        showDot(px, py, true)
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (e: Exception) {}
        try { recognizer?.close() } catch (e: Exception) {}
        try { dot?.let { wm?.removeView(it) } } catch (e: Exception) {}
        dot = null
        exec.shutdown()
        started = false
        super.onDestroy()
    }
}
