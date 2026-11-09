
package com.example.liveroulette

import android.app.Activity
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

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_CAPTURE = 1002
    }

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }

        val title = TextView(this).apply {
            text = "LIVE ROULETTE AI"
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 25)
        }

        statusText = TextView(this).apply {
            text = "Status: STOPPED"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        val startButton = Button(this).apply {
            text = "START SCREEN CAPTURE"
            setOnClickListener {
                startCapture()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP SERVICE"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, CaptureService::class.java)
                )
                statusText.text = "Status: STOPPED"
                Toast.makeText(
                    this@MainActivity,
                    "Capture stopped",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            statusText.text = "Allow overlay permission first"

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        try {
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CAPTURE
            )
        } catch (e: Exception) {
            statusText.text = "Screen capture permission error"
            Toast.makeText(
                this,
                e.message ?: "Unable to request screen capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Using activity result for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {

            REQUEST_OVERLAY -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(this)
                ) {
                    statusText.text = "Overlay allowed. Tap START again."
                } else {
                    statusText.text = "Overlay permission denied"
                }
            }

            REQUEST_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startCaptureService(resultCode, data)
                } else {
                    statusText.text = "Screen capture cancelled"
                }
            }
        }
    }

    private fun startCaptureService(
        resultCode: Int,
        data: Intent
    ) {
        val serviceIntent = Intent(
            this,
            CaptureService::class.java
        ).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            statusText.text = "Status: SERVICE STARTED"

            Toast.makeText(
                this,
                "Screen capture started",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            statusText.text = "Service could not start"

            Toast.makeText(
                this,
                e.message ?: "Service error",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
