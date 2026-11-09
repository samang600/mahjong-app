package io.github.mahjongapp.assist

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.github.mahjongapp.R
import kotlin.math.abs

/**
 * Screen-recognition assistant (test version): a floating "AI" button over any app. Tapping it
 * captures one frame of the screen (MediaProjection), recognises the mahjong hand and shows the
 * recommended discard, with a ring around that tile. Long-press the button to stop.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "io.github.mahjongapp.assist.STOP"
        private const val CHANNEL = "assist"
        private const val NOTIFICATION_ID = 7
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0; private var capH = 0
    private var bubble: TextView? = null
    private var panel: TextView? = null
    private var marker: MarkerView? = null
    private var analyzer: AssistAnalyzer? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        // Must be in the foreground (type mediaProjection) before obtaining the projection.
        startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        worker = HandlerThread("assist").apply { start() }
        workerHandler = Handler(worker.looper)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        analyzer = AssistAnalyzer(this)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        createCapture()
        addOverlays()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.assist_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.assist_title))
            .setContentText(getString(R.string.assist_running))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.assist_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    // ------------------------------------------------------------------ capture

    private fun screenSize(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    /** (Re)creates the capture surface at the current screen size (e.g. after rotating). */
    private fun createCapture() {
        val (w, h, dpi) = screenSize()
        if (w == capW && h == capH && display != null) return
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        capW = w; capH = h
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay(
                "mahjong-assist", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, workerHandler,
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = r.surface
        }
    }

    private fun analyzeNow() {
        if (busy) return
        busy = true
        marker?.mark = null
        panel?.text = getString(R.string.assist_analyzing)
        createCapture()
        // Give the screen a moment to redraw without the old marker, then grab the latest frame.
        workerHandler.postDelayed({
            val bmp = grabFrame()
            val result = if (bmp == null) null else analyzer?.analyze(bmp)
            main.post {
                busy = false
                if (result == null) {
                    panel?.text = getString(R.string.assist_no_frame)
                } else {
                    panel?.text = result.text
                    marker?.mark = result.mark
                }
            }
        }, 250)
    }

    private fun grabFrame(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
        } finally {
            img.close()
        }
    }

    // ------------------------------------------------------------------ overlays

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlays() {
        val dp = resources.displayMetrics.density
        // Marker layer (full screen, not touchable) at the bottom of our overlay stack.
        val mk = MarkerView(this)
        marker = mk
        wm.addView(mk, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false).apply { x = 0; y = 0 })

        val pv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.argb(220, 0x10, 0x18, 0x20)) }
            text = getString(R.string.assist_ready)
            maxWidth = (360 * dp).toInt()
        }
        panel = pv
        val panelParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)

        val bv = TextView(this).apply {
            text = "AI"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0xE5, 0x39, 0x35)); setStroke((2 * dp).toInt(), Color.WHITE) }
        }
        bubble = bv
        val size = (56 * dp).toInt()
        val (sw, sh, _) = screenSize()
        val bubbleParams = overlayParams(size, size, true).apply { x = sw - size - (16 * dp).toInt(); y = sh / 3 }
        panelParams.x = maxOf(0, bubbleParams.x - (370 * dp).toInt()); panelParams.y = bubbleParams.y

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) + abs(dy) > 12 * dp) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(bv, bubbleParams)
                        panelParams.x = maxOf(0, bubbleParams.x - (370 * dp).toInt()); panelParams.y = bubbleParams.y
                        wm.updateViewLayout(pv, panelParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - e.downTime > 700) stopSelf() else analyzeNow()
                }
            }
            true
        }
        wm.addView(pv, panelParams)
        wm.addView(bv, bubbleParams)
    }

    override fun onDestroy() {
        for (v in listOf<View?>(bubble, panel, marker)) {
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
        }
        display?.release()
        reader?.close()
        projection?.stop()
        projection = null
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    /** Draws a ring around the recommended tile (screen coordinates). */
    private class MarkerView(context: Context) : View(context) {
        var mark: Rect? = null
            set(v) { field = v; postInvalidate() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(0x00, 0xE6, 0x76) }
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            val r = mark ?: return
            // The overlay window may not start at the screen origin; convert screen -> view coordinates.
            getLocationOnScreen(loc)
            ring.strokeWidth = r.width() * 0.08f
            val rf = RectF(r).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()); inset(-r.width() * 0.06f, -r.width() * 0.06f) }
            canvas.drawRoundRect(rf, r.width() * 0.15f, r.width() * 0.15f, ring)
        }
    }
}
