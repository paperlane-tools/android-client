package com.yzj.client

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG: Int = Color.rgb(244, 247, 245)
    val CARD: Int = Color.WHITE
    val STROKE: Int = Color.rgb(226, 232, 229)
    val TEXT: Int = Color.rgb(25, 35, 30)
    val MUTED: Int = Color.rgb(100, 112, 105)
    val ACCENT: Int = Color.rgb(10, 122, 61)
    val ACCENT_SOFT: Int = Color.rgb(232, 246, 238)
    val WARN: Int = Color.rgb(180, 83, 9)
    val DANGER: Int = Color.rgb(185, 45, 45)

    fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

    fun rounded(context: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(context, 1), stroke)
        }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
        background = rounded(context, CARD, STROKE, 16)
    }

    fun text(context: Context, value: String, size: Int, color: Int): TextView =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
        }

    fun title(context: Context, value: String): TextView =
        text(context, value, 25, TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

    fun button(context: Context, label: String, primary: Boolean): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else ACCENT)
            setPadding(dp(context, 13), dp(context, 9), dp(context, 13), dp(context, 9))
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(
                        context,
                        if (primary) Color.rgb(7, 100, 50) else ACCENT_SOFT,
                        if (primary) ACCENT else ACCENT_SOFT,
                        10
                    )
                )
                addState(
                    intArrayOf(),
                    rounded(
                        context,
                        if (primary) ACCENT else Color.WHITE,
                        if (primary) ACCENT else STROKE,
                        10
                    )
                )
            }
        }

    fun chip(context: Context, label: String, color: Int): TextView =
        text(context, label, 11, color).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
            background = rounded(context, Color.TRANSPARENT, color, 99)
        }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun margin(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.setMargins(
            dp(view.context, left),
            dp(view.context, top),
            dp(view.context, right),
            dp(view.context, bottom)
        )
        view.layoutParams = lp
    }
}
