
package com.example.liveroulette

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 40)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "LIVE ROULETTE AI"
            textSize = 24f
            setTextColor(-1)
        }
        page.addView(title)

        status = TextView(this).apply {
            text = "STATUS: READY"
            textSize = 16f
            setTextColor(0xFF00FF00.toInt())
            setPadding(0, 25, 0, 25)
        }
        page.addView(status)

        val permission = Button(this).apply {
            text = "ALLOW FLOATING PANEL"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Permission allowed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        page.addView(permission)

        val start = Button(this).apply {
            text = "START PANEL"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(
                        Intent(this@MainActivity, CaptureService::class.java)
                    )
                    status.text = "STATUS: PANEL STARTED"
                } else {
                    status.text = "Please allow floating permission first"
                }
            }
        }
        page.addView(start)

        val stop = Button(this).apply {
            text = "STOP PANEL"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, CaptureService::class.java)
                )
                status.text = "STATUS: STOPPED"
            }
        }
        page.addView(stop)

        setContentView(page)
    }
}
