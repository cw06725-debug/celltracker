package com.example.celltracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/** Purely visual styling: no change to event interception, tap timing or permissions. */
internal object OverlayStyleV130 {
    private fun dp(ctx: Context, value: Int) = (ctx.resources.displayMetrics.density * value + .5f).toInt()

    fun card(ctx: Context, opacity: Float = .88f): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 18).toFloat()
        val a = (opacity.coerceIn(.35f, 1f) * 255).toInt().coerceIn(89, 255)
        setColor(Color.argb(a, 13, 42, 86))
        setStroke(dp(ctx, 1).coerceAtLeast(1), Color.argb(195, 85, 169, 255))
    }

    fun button(ctx: Context, button: Button, destructive: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 12f
        button.minHeight = dp(ctx, 38)
        button.minimumHeight = dp(ctx, 38)
        button.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
        button.backgroundTintList = ColorStateList.valueOf(
            if (destructive) Color.rgb(71, 93, 126) else Color.rgb(25, 115, 230)
        )
        button.setTextColor(Color.WHITE)
    }
}
