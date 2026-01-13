package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.PrintWriter

object ToastAPI {

    private const val LOG_TAG = "ToastAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val durationExtra = if (intent.getBooleanExtra("short", false)) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        val backgroundColor = getColorExtra(intent, "background", Color.GRAY)
        val textColor = getColorExtra(intent, "text_color", Color.WHITE)
        val gravity = getGravityExtra(intent)

        val handler = Handler()

        ResultReturner.returnData(context, intent, object : ResultReturner.WithStringInput() {
            override fun writeResult(out: PrintWriter) {
                handler.post {
                    val toast = Toast.makeText(context, inputString, durationExtra)
                    val toastView = toast.view

                    toastView?.background?.setTint(backgroundColor)

                    val textView = toastView?.findViewById<TextView>(android.R.id.message)
                    textView?.setTextColor(textColor)

                    toast.setGravity(gravity, 0, 0)
                    toast.show()
                }
            }
        })
    }

    @JvmStatic
    protected fun getColorExtra(intent: Intent, extra: String, defaultColor: Int): Int {
        var color = defaultColor

        if (intent.hasExtra(extra)) {
            val colorExtra = intent.getStringExtra(extra)
            try {
                color = Color.parseColor(colorExtra)
            } catch (e: IllegalArgumentException) {
                Logger.logError(LOG_TAG, "Failed to parse color '$colorExtra' for '$extra'")
            }
        }
        return color
    }

    @JvmStatic
    protected fun getGravityExtra(intent: Intent): Int {
        return when (intent.getStringExtra("gravity")) {
            "top" -> Gravity.TOP
            "middle" -> Gravity.CENTER
            "bottom" -> Gravity.BOTTOM
            else -> Gravity.CENTER
        }
    }
}
