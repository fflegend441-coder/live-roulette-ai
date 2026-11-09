package com.example.liveroulette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque
import java.util.regex.Pattern
import kotlin.math.abs

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "roulette_capture"
        private const val NOTIFICATION_ID = 73
        private const val PREFS = "roulette_overlay"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var overlay: LinearLayout? = null
    private var resultView: TextView? = null
    private var toggleButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var scanEnabled = false
    private var busy = false
    private var lastNumber: Int? = null
    private var lastSeenAt = 0L
    private var lastPredictionRoundAt = 0L
    private val history = ArrayDeque<Int>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val numberPattern = Pattern.compile("(?<![0-9])(?:[0-9]|[1-2][0-9]|3[0-6])(?![0-9])")
    private val redNumbers = setOf(1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        worker = HandlerThread("roulette-screen-reader").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = getProjectionData(intent)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, workerHandler)
            startReader()
        } catch (e: Exception) {
            showMessage("Could not start screen reading")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent?): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    else intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Roulette screen reader", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Immersive Roulette AI")
            .setContentText("Tap STAR on the floating panel to enable OCR")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    @Suppress("DEPRECATION")
    private fun startReader() {
        val metrics = android.util.DisplayMetrics()
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(metrics)
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image != null) processImage(image)
        }, workerHandler)
        virtualDisplay = projection?.createVirtualDisplay(
            "RouletteOCR", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, workerHandler
        )
        showMessage("Tap STAR to start OCR")
    }

    private fun processImage(image: Image) {
        // No OCR or analysis is performed unless the user explicitly turns STAR on.
        if (!scanEnabled || busy) { image.close(); return }
        busy = true
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) { busy = false; return }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = result.text
                if (!looksLikeRouletteScreen(text)) return@addOnSuccessListener
                val matcher = numberPattern.matcher(text)
                val found = mutableListOf<Int>()
                while (matcher.find()) matcher.group()?.toIntOrNull()?.let { if (it in 0..36) found.add(it) }
                val candidate = found.lastOrNull()
                if (candidate != null) acceptCandidate(candidate)
            }
            .addOnCompleteListener { bitmap.recycle(); busy = false }
    }

    private fun looksLikeRouletteScreen(text: String): Boolean {
        val upper = text.uppercase()
        val matcher = numberPattern.matcher(text)
        val unique = mutableSetOf<Int>()
        while (matcher.find()) matcher.group()?.toIntOrNull()?.let { if (it in 0..36) unique.add(it) }
        val rouletteWords = upper.contains("ROULETTE") || (upper.contains("EVEN") && upper.contains("ODD"))
        return rouletteWords && unique.size >= 8
    }

    private fun imageToBitmap(image: Image): Bitmap? = try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind(); full.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        full.recycle(); cropped
    } catch (_: Exception) { null }

    private fun acceptCandidate(number: Int) {
        val now = System.currentTimeMillis()
        // A stable result visible across many OCR frames is one round, not many rounds.
        // Suppress repeats; a changed result is treated as the next completed round.
        if (number == lastNumber && now - lastSeenAt < 15000L) return
        lastNumber = number
        lastSeenAt = now
        history.addFirst(number)
        while (history.size > 50) history.removeLast()

        // Show exactly one compact estimate after a newly detected result.
        // This is a descriptive frequency heuristic, not a reliable roulette prediction.
        val counts = (0..36).associateWith { n -> history.count { it == n } }
        val guess = counts.entries
            .filter { it.key != number }
            .minWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            ?.key ?: 0
        lastPredictionRoundAt = now
        val color = when { number == 0 -> "GREEN"; number in redNumbers -> "RED"; else -> "BLACK" }
        showPrediction(guess, "After result: $number ($color)")
    }

    private fun createOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        resultView = TextView(this).apply {
            text = "NEXT POSSIBLE\n—"
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 10, 16, 4)
        }
        toggleButton = Button(this).apply {
            text = "☆ STAR OFF"; textSize = 12f; isAllCaps = false
            setOnClickListener {
                scanEnabled = !scanEnabled
                text = if (scanEnabled) "★ STAR ON" else "☆ STAR OFF"
                if (scanEnabled) showMessage("Waiting for roulette screen…") else showMessage("OCR PAUSED\nTap STAR to resume")
            }
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6223444.toInt())
            addView(resultView)
            addView(toggleButton)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 12); y = prefs.getInt("y", 120)
        }
        overlayParams = params
        overlay?.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                        if (abs(dx) + abs(dy) > 8) dragged = true
                        params.x = startX + dx; params.y = startY + dy
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                        return dragged
                    }
                }
                return false
            }
        })
        try { wm.addView(overlay, params) } catch (_: Exception) { overlay = null }
    }

    private fun showPrediction(number: Int, detail: String) {
        resultView?.post {
            resultView?.text = "NEXT GUESS\n$number\n$detail"
            resultView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            resultView?.setTextColor(0xFF00E676.toInt())
            resultView?.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun showMessage(message: String) {
        resultView?.post { resultView?.text = message; resultView?.textSize = 13f; resultView?.setTextColor(0xFFFFFFFF.toInt()) }
    }

    override fun onDestroy() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        overlay = null; worker?.quitSafely(); recognizer.close(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
