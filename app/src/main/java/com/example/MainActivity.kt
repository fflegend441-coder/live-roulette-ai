package com.example.liveroulette

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val captureRequest = 4102
    private val overlayRequest = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFF101820.toInt())
        }

        fun label(value: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(4, 12, 4, 12)
            }

        root.addView(label("IMMERSIVE ROULETTE AI", 23f))
        status = label("STATUS: READY", 16f, 0xFF70E090.toInt())
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. ALLOW FLOATING PANEL"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Permission already allowed", Toast.LENGTH_SHORT).show()
                } else {
                    val permissionIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(permissionIntent, overlayRequest)
                }
            }
        })

        root.addView(Button(this).apply {
            text = "2. START SCREEN READING"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    status.text = "Allow floating panel permission first"
                    return@setOnClickListener
                }
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })

        root.addView(Button(this).apply {
            text = "STOP DETECTION"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "STATUS: STOPPED"
            }
        })

        root.addView(label(
            "Turn STAR ON only when the roulette table is visible. STAR OFF pauses OCR.",
            13f, 0xFFB8C7D1.toInt()
        ))
        root.addView(label(
            "Roulette is random. Statistical guesses are not guaranteed.",
            12f, 0xFFFFD18A.toInt()
        ))

        setContentView(root)
    }

    @Deprecated("Uses the activity result API for compatibility with the supplied project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == overlayRequest) {
            status.text = if (Settings.canDrawOverlays(this)) {
                "STATUS: FLOATING PANEL ALLOWED"
            } else {
                "STATUS: OVERLAY PERMISSION NEEDED"
            }
            return
        }

        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    status.text = "STATUS: SCREEN READING STARTED"
                } catch (e: Exception) {
                    status.text = "Could not start screen reader: ${e.javaClass.simpleName}"
                }
            } else {
                status.text = "SCREEN CAPTURE PERMISSION NOT GRANTED"
            }
        }
    }
}
