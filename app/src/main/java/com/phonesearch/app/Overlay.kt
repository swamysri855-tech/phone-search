package com.phonesearch.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** కాల్ వచ్చినప్పుడు స్క్రీన్ పైన పేరు చూపించే బాక్స్. */
object Overlay {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private val autoDismiss = Runnable { removeNow() }

    fun show(ctx: Context, name: String, number: String) {
        val app = ctx.applicationContext
        handler.post {
            removeNow()
            if (!Settings.canDrawOverlays(app)) return@post
            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = app.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val box = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6102A43"))
                    cornerRadius = dp(16).toFloat()
                }
                addView(TextView(app).apply {
                    text = name
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                addView(TextView(app).apply {
                    text = number
                    textSize = 14f
                    setTextColor(Color.parseColor("#B0C4DE"))
                    gravity = Gravity.CENTER
                })
                setOnClickListener { removeNow() }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                x = 0
                y = dp(90)
                horizontalMargin = 0.04f
            }
            try {
                manager.addView(box, lp)
                view = box
                wm = manager
                handler.postDelayed(autoDismiss, 45_000)
            } catch (e: Exception) {
                // అనుమతి లేకపోతే చూపించదు
            }
        }
    }

    fun dismiss() {
        handler.post { removeNow() }
    }

    private fun removeNow() {
        handler.removeCallbacks(autoDismiss)
        val v = view ?: return
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
        }
        view = null
    }
}
