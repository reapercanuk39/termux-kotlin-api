package com.termux.api.util

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.termux.shared.theme.ThemeUtils

object ViewUtils {

    @JvmStatic
    fun setWarningTextViewAndButtonState(
        context: Context,
        textView: TextView,
        button: Button,
        warningState: Boolean,
        text: String
    ) {
        if (warningState) {
            textView.setTextColor(ContextCompat.getColor(context, com.termux.shared.R.color.red_error))
            textView.setLinkTextColor(ContextCompat.getColor(context, com.termux.shared.R.color.red_error_link))
            button.isEnabled = true
            button.alpha = 1f
        } else {
            textView.setTextColor(ThemeUtils.getTextColorPrimary(context))
            textView.setLinkTextColor(ThemeUtils.getTextColorLink(context))
            button.isEnabled = false
            button.alpha = 0.5f
        }

        button.text = text
    }
}
