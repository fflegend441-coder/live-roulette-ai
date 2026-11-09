
package com.example.liveroulette

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class CaptureService : Service() {

    private lateinit var windowManager: WindowManager
    private var panel: TextView? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        panel = TextView(this).apply {
            text = "LIVE ROULETTE AI\nPanel ready\nLive detection not started"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(25, 40, 55))
            setPadding(20, 16, 20, 16)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 150
        }

        windowManager.addView(panel, params)
    }

    override fun onDestroy() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
