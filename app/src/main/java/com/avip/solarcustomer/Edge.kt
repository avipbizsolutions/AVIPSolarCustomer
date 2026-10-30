package com.avip.solarcustomer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android 15+ draws every app edge to edge (behind the status and navigation
 * bars). This keeps the screens as they were: the content is padded clear of
 * the bars and the keyboard, and the strips behind the bars are painted in
 * the given colours. Call in onCreate, before setContentView.
 */
fun AppCompatActivity.edgeToEdge(statusColor: Int, navColor: Int, lightNavIcons: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT < 35) {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = lightNavIcons
    }
    val content = findViewById<ViewGroup>(android.R.id.content)
    val strips = BarStrips(statusColor, navColor)
    content.background = strips
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = maxOf(bars.bottom, ime.bottom)
        v.setPadding(bars.left, bars.top, bars.right, bottom)
        strips.top = bars.top
        strips.bottom = bottom
        strips.invalidateSelf()
        WindowInsetsCompat.CONSUMED
    }
}

/** Paints the strip behind the status bar and the one behind the navigation bar / keyboard. */
private class BarStrips(statusColor: Int, navColor: Int) : Drawable() {
    var top = 0
    var bottom = 0
    private val topPaint = Paint().apply { color = statusColor }
    private val bottomPaint = Paint().apply { color = navColor }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (top > 0) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + top).toFloat(), topPaint)
        if (bottom > 0) canvas.drawRect(b.left.toFloat(), (b.bottom - bottom).toFloat(), b.right.toFloat(), b.bottom.toFloat(), bottomPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
