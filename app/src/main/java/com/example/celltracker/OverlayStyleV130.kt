package com.example.celltracker

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Consistent visual tokens for the Android View-based test overlays.
 *  No animation timers: overlays must remain cheap and never interfere with testing.
 *  Alpha is derived from the existing user opacity setting.
 */
internal object OverlayStyleV130 {
    private fun dp(ctx: Context, value: Int) = (ctx.resources.displayMetrics.density * value + .5f).toInt()
    private fun dark(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun textColor(ctx: Context): Int = if (dark(ctx)) Color.rgb(248, 244, 253) else Color.rgb(57, 46, 78)
    fun secondaryTextColor(ctx: Context): Int = if (dark(ctx)) Color.rgb(207, 196, 222) else Color.rgb(106, 96, 129)

    fun card(ctx: Context, opacity: Float = .88f): GradientDrawable {
        val colors = if (dark(ctx)) intArrayOf(0xFF40344F.toInt(), 0xFF3E3348.toInt(), 0xFF27413F.toInt())
        else intArrayOf(0xFFF0E7FB.toInt(), 0xFFFCECF3.toInt(), 0xFFE3F6ED.toInt())
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(ctx, 22).toFloat()
            alpha = (opacity.coerceIn(.35f, 1f) * 255f).toInt()
            setStroke(dp(ctx, 1).coerceAtLeast(1), if (dark(ctx)) 0xFF8B79A4.toInt() else 0xFFE1D7EF.toInt())
        }
    }

    fun button(ctx: Context, button: Button, destructive: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 12f
        button.minHeight = dp(ctx, 38)
        button.minimumHeight = dp(ctx, 38)
        button.setPadding(dp(ctx, 11), dp(ctx, 4), dp(ctx, 11), dp(ctx, 4))
        val colors = if (destructive) intArrayOf(0xFF917A9F.toInt(), 0xFF7B7698.toInt())
        else intArrayOf(0xFF7865B3.toInt(), 0xFF6C86AC.toInt(), 0xFF458E87.toInt())
        val background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(ctx, 15).toFloat()
        }
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), background, null)
        button.setTextColor(Color.WHITE)
    }
}
