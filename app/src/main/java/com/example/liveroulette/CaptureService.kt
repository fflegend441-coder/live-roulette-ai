package com.example.liveroulette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private var header: LinearLayout? = null
    private var contentPanel: LinearLayout? = null
    private var resultView: TextView? = null
    private var detailView: TextView? = null
    private var toggleButton: Button? = null
    private var lockButton: Button? = null
    private var minimizeButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    @Volatile private var scanEnabled = false
    @Volatile private var busy = false
    private var locked = false
    private var minimized = false

    private var stableCandidate: Int? = null
    private var stableFrames = 0
    private var lastAcceptedNumber: Int? = null

    private val history = ArrayDeque<Int>()
    private val recognizerLazy = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val recognizer by recognizerLazy

    private val numberPattern =
        Pattern.compile("(?<![0-9])(?:[0-9]|[1-2][0-9]|3[0-6])(?![0-9])")

    private val redNumbers = setOf(
        1, 3, 5, 7, 9, 12, 14, 16, 18,
        19, 21, 23, 25, 27, 30, 32, 34, 36
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        worker = HandlerThread("roulette-screen-reader").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = getProjectionData(intent)

        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, workerHandler)

            startReader()
        } catch (e: Exception) {
            showMessage("Screen reading could not start")
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Roulette screen reader",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Immersive Roulette AI")
            .setContentText("STAR must be ON to run OCR")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startReader() {
        val metrics = android.util.DisplayMetrics()
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(metrics)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
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
            imageReader?.surface,
            null,
            workerHandler
        )

        showMessage("STAR OFF — OCR paused")
    }

    private fun processImage(image: Image) {
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

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (!scanEnabled) return@addOnSuccessListener

                    val text = result.text
                    if (!looksLikeRouletteScreen(text)) {
                        stableCandidate = null
                        stableFrames = 0
                        return@addOnSuccessListener
                    }

                    val candidate = extractCandidate(text)
                    if (candidate != null) registerStableCandidate(candidate)
                }
                .addOnFailureListener {
                    // Ignore a single failed OCR frame; the next frame can retry.
                }
                .addOnCompleteListener {
                    bitmap.recycle()
                    busy = false
                }
        } catch (_: Exception) {
            bitmap.recycle()
            busy = false
        }
    }

    private fun looksLikeRouletteScreen(text: String): Boolean {
        val upper = text.uppercase()
        val matcher = numberPattern.matcher(text)
        val unique = mutableSetOf<Int>()

        while (matcher.find()) {
            matcher.group()?.toIntOrNull()?.let { if (it in 0..36) unique.add(it) }
        }

        val rouletteWords = upper.contains("ROULETTE") ||
            (upper.contains("EVEN") && upper.contains("ODD"))

        return rouletteWords && unique.size >= 8
    }

    private fun extractCandidate(text: String): Int? {
        // Prefer an explicitly labelled result if the game displays one.
        val labelled = Regex(
            "(?i)(?:winning\s*number|last\s*number|result)\s*[:#-]?\s*(3[0-6]|[0-2]?\d)"
        ).find(text)

        if (labelled != null) return labelled.groupValues[1].toIntOrNull()

        // Fallback: last OCR number. This can be wrong on layouts where the
        // roulette number grid is read after the result; verify on the real screen.
        val matcher = numberPattern.matcher(text)
        var candidate: Int? = null
        while (matcher.find()) {
            candidate = matcher.group()?.toIntOrNull()
        }
        return candidate
    }

    private fun registerStableCandidate(number: Int) {
        if (stableCandidate == number) {
            stableFrames++
        } else {
            stableCandidate = number
            stableFrames = 1
        }

        if (stableFrames < 3) return
        if (number == lastAcceptedNumber) return

        lastAcceptedNumber = number
        acceptCandidate(number)
    }

    private fun acceptCandidate(number: Int) {
        history.addFirst(number)
        while (history.size > 50) history.removeLast()

        val counts = (0..36).associateWith { n -> history.count { it == n } }
        val guess = counts.entries
            .filter { it.key != number }
            .minWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }
            )?.key ?: 0

        val color = when {
            number == 0 -> "GREEN"
            number in redNumbers -> "RED"
            else -> "BLACK"
        }

        showPrediction(guess, "Last detected: $number ($color)")
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = image.width +
                (rowStride - pixelStride * image.width) / pixelStride

            val full = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(
                full, 0, 0, image.width, image.height
            )
            full.recycle()
            cropped
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val title = TextView(this).apply {
            text = "ROULETTE AI  •  DRAG"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12, 10, 12, 10)
        }

        lockButton = Button(this).apply {
            text = "UNLOCK"
            isAllCaps = false
            setOnClickListener {
                locked = !locked
                text = if (locked) "LOCKED" else "UNLOCK"
            }
        }

        minimizeButton = Button(this).apply {
            text = "MINIMIZE"
            isAllCaps = false
            setOnClickListener {
                minimized = !minimized
                contentPanel?.visibility = if (minimized) View.GONE else View.VISIBLE
                text = if (minimized) "RESTORE" else "MINIMIZE"
            }
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263746.toInt())
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lockButton)
            addView(minimizeButton)
        }

        resultView = TextView(this).apply {
            text = "—"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(0xFF00E676.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(12, 12, 12, 2)
        }

        detailView = TextView(this).apply {
            text = "NEXT POSSIBLE NUMBER"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 2, 8, 8)
        }

        toggleButton = Button(this).apply {
            text = "☆ STAR OFF"
            isAllCaps = false
            setOnClickListener {
                scanEnabled = !scanEnabled
                stableCandidate = null
                stableFrames = 0
                text = if (scanEnabled) "★ STAR ON" else "☆ STAR OFF"

                if (scanEnabled) {
                    showMessage("Waiting for roulette screen…")
                } else {
                    showMessage("OCR PAUSED")
                }
            }
        }

        contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resultView)
            addView(detailView)
            addView(toggleButton)
        }

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6223444.toInt())
            addView(header)
            addView(contentPanel)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 12)
            y = prefs.getInt("y", 120)
        }

        overlayParams = params

        // Only the title area drags the panel, so the buttons remain clickable.
        title.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (locked) return true
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) + abs(dy) > 8) dragged = true
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            overlay?.let { wm.updateViewLayout(it, params) }
                        } catch (_: Exception) {
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragged) {
                            prefs.edit()
                                .putInt("x", params.x)
                                .putInt("y", params.y)
                                .apply()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(overlay, params)
        } catch (_: Exception) {
            overlay = null
        }
    }

    private fun showPrediction(number: Int, detail: String) {
        resultView?.post {
            resultView?.text = number.toString()
            resultView?.textSize = 42f
            resultView?.setTextColor(0xFF00E676.toInt())
            detailView?.text = "NEXT POSSIBLE NUMBER"
            detailView?.setTextColor(0xFFFFFFFF.toInt())
        }
        detailView?.post {
            detailView?.text = "NEXT POSSIBLE NUMBER\n$detail"
        }
    }

    private fun showMessage(message: String) {
        resultView?.post {
            resultView?.text = "—"
            resultView?.textSize = 30f
            resultView?.setTextColor(0xFFFFFFFF.toInt())
            detailView?.text = message
        }
    }

    override fun onDestroy() {
        scanEnabled = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        try {
            overlay?.let {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        } catch (_: Exception) {
        }

        overlay = null
        worker?.quitSafely()

        if (recognizerLazy.isInitialized()) {
            recognizer.close()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
