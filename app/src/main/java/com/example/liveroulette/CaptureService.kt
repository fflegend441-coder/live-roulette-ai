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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque
import java.util.regex.Pattern

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "roulette_capture"
        private const val NOTIFICATION_ID = 73
        private const val PREFS = "roulette_overlay"
        private const val MAX_HISTORY = 50
        private const val STABLE_FRAMES_REQUIRED = 3
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var resultView: TextView? = null
    private var dragHandle: TextView? = null
    private var starButton: Button? = null
    private var lockButton: Button? = null
    private var minimizeButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    @Volatile private var scanEnabled = false
    @Volatile private var busy = false
    private var panelLocked = false
    private var minimized = false

    private var pendingNumber: Int? = null
    private var pendingCount = 0
    private var lastAcceptedNumber: Int? = null
    private var waitingForRoundChange = false

    private val history = ArrayDeque<Int>()
    private var recognizer: TextRecognizer? = null

    private val numberPattern =
        Pattern.compile("(?<![0-9])(?:[0-9]|[1-2][0-9]|3[0-6])(?![0-9])")

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        worker = HandlerThread("roulette-screen-reader").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = getProjectionData(intent)

        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                workerHandler
            )

            startReader()
        } catch (_: Exception) {
            showMessage("Screen capture could not start")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Roulette screen reader",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Immersive Roulette AI")
            .setContentText("Turn STAR on to scan a roulette screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startReader() {
        val metrics = android.util.DisplayMetrics()
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(metrics)

        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            3
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (image != null) processImage(image)
        }, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "RouletteOCR",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )

        showMessage("Tap STAR to start")
    }

    private fun processImage(image: Image) {
        // STAR OFF means no bitmap conversion, OCR, or analysis.
        if (!scanEnabled || busy) {
            image.close()
            return
        }

        busy = true
        val bitmap = imageToBitmap(image)
        image.close()

        if (bitmap == null) {
            busy = false
            return
        }

        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer?.process(input)
            ?.addOnSuccessListener { result ->
                // STAR may have been switched off while ML Kit was processing.
                if (!scanEnabled) return@addOnSuccessListener

                val text = result.text
                if (!looksLikeRouletteScreen(text)) {
                    pendingNumber = null
                    pendingCount = 0
                    return@addOnSuccessListener
                }

                val candidate = extractCandidate(text)
                if (candidate == null) {
                    pendingNumber = null
                    pendingCount = 0
                } else {
                    registerStableCandidate(candidate)
                }
            }
            ?.addOnFailureListener {
                // Ignore a single OCR failure; the next frame can be processed.
            }
            ?.addOnCompleteListener {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy = false
            } ?: run {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy = false
            }
    }

    private fun looksLikeRouletteScreen(text: String): Boolean {
        val upper = text.uppercase()
        val matcher = numberPattern.matcher(text)
        val unique = mutableSetOf<Int>()

        while (matcher.find()) {
            matcher.group()?.toIntOrNull()?.let {
                if (it in 0..36) unique.add(it)
            }
        }

        val rouletteWords =
            upper.contains("ROULETTE") ||
            (upper.contains("EVEN") && upper.contains("ODD"))

        return rouletteWords && unique.size >= 8
    }

    /*
     * OCR text order depends on the roulette website layout. This takes the
     * final recognized 0..36 token as a candidate; a screenshot/ROI of the
     * exact result-history area is needed to make this extraction site-specific.
     */
    private fun extractCandidate(text: String): Int? {
        val matcher = numberPattern.matcher(text)
        var last: Int? = null

        while (matcher.find()) {
            val value = matcher.group()?.toIntOrNull()
            if (value != null && value in 0..36) last = value
        }

        return last
    }

    private fun registerStableCandidate(number: Int) {
        if (pendingNumber == number) {
            pendingCount++
        } else {
            pendingNumber = number
            pendingCount = 1
        }

        if (pendingCount < STABLE_FRAMES_REQUIRED) return

        if (lastAcceptedNumber == null) {
            acceptRound(number)
            return
        }

        if (number != lastAcceptedNumber) {
            // A changed, stable OCR value is counted once as a new result.
            acceptRound(number)
        }
        // Same value remains ignored until OCR observes a different value.
    }

    private fun acceptRound(number: Int) {
        lastAcceptedNumber = number
        waitingForRoundChange = true

        history.addFirst(number)
        while (history.size > MAX_HISTORY) history.removeLast()

        val counts = (0..36).associateWith { n ->
            history.count { it == n }
        }

        // A frequency-based display heuristic only; roulette outcomes are random.
        val guess = counts.entries
            .filter { it.key != number }
            .minWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }
                    .thenBy { it.key }
            )?.key ?: 0

        showPrediction(guess)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            fullBitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(
                fullBitmap,
                0,
                0,
                image.width,
                image.height
            )
            fullBitmap.recycle()
            cropped
        } catch (_: Exception) {
            null
        }
    }

    private fun createOverlay() {
        if (Build.VERSION.SDK_INT >= 23 &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        resultView = TextView(this).apply {
            text = "—"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(0xFF00E676.toInt())
            setPadding(18, 12, 18, 12)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        dragHandle = TextView(this).apply {
            text = "ROULETTE AI  •  MOVE"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFF263746.toInt())
        }

        starButton = Button(this).apply {
            text = "☆ STAR OFF"
            isAllCaps = false
            setOnClickListener {
                scanEnabled = !scanEnabled
                text = if (scanEnabled) "★ STAR ON" else "☆ STAR OFF"
                if (scanEnabled) {
                    pendingNumber = null
                    pendingCount = 0
                    showMessage("Waiting for roulette screen")
                } else {
                    pendingNumber = null
                    pendingCount = 0
                    showMessage("PAUSED")
                }
            }
        }

        lockButton = Button(this).apply {
            text = "UNLOCK"
            isAllCaps = false
            setOnClickListener {
                panelLocked = !panelLocked
                text = if (panelLocked) "LOCKED" else "UNLOCK"
            }
        }

        minimizeButton = Button(this).apply {
            text = "MINIMIZE"
            isAllCaps = false
            setOnClickListener {
                minimized = !minimized
                resultView?.visibility =
                    if (minimized) View.GONE else View.VISIBLE
                starButton?.visibility =
                    if (minimized) View.GONE else View.VISIBLE
                lockButton?.visibility =
                    if (minimized) View.GONE else View.VISIBLE
                text = if (minimized) "RESTORE" else "MINIMIZE"
            }
        }

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6223444.toInt())
            addView(dragHandle)
            addView(resultView)
            addView(starButton)
            addView(lockButton)
            addView(minimizeButton)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 12)
            y = prefs.getInt("y", 120)
        }

        overlayParams = params
        attachDragListener(dragHandle, params, prefs)

        try {
            windowManager?.addView(overlay, params)
        } catch (_: Exception) {
            overlay = null
            showMessage("Overlay permission required")
        }
    }

    private fun attachDragListener(
        handle: View?,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences
    ) {
        handle?.setOnTouchListener { view, event ->
            if (panelLocked) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.tag = floatArrayOf(
                        event.rawX,
                        event.rawY,
                        params.x.toFloat(),
                        params.y.toFloat()
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val start = view.tag as? FloatArray
                    if (start != null) {
                        params.x = start[2].toInt() +
                            (event.rawX - start[0]).toInt()
                        params.y = start[3].toInt() +
                            (event.rawY - start[1]).toInt()
                        try {
                            windowManager?.updateViewLayout(overlay, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    prefs.edit()
                        .putInt("x", params.x)
                        .putInt("y", params.y)
                        .apply()
                    true
                }

                else -> true
            }
        }
    }

    private fun showPrediction(number: Int) {
        resultView?.post {
            resultView?.text = number.toString()
            resultView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            resultView?.setTextColor(0xFF00E676.toInt())
            resultView?.gravity = Gravity.CENTER
            resultView?.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun showMessage(message: String) {
        resultView?.post {
            resultView?.text = message
            resultView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            resultView?.setTextColor(0xFFFFFFFF.toInt())
            resultView?.gravity = Gravity.CENTER
        }
    }

    override fun onDestroy() {
        scanEnabled = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null

        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null

        worker?.quitSafely()
        worker = null
        workerHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
