package com.avip.solarcustomer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launch screen. Updates are compulsory, like the other AVIP apps: if a newer
 * version exists, it is installed here and the app does not open on the old
 * version. The website build updates from GitHub, the Play Store build through
 * Google Play (see UpdateGate in src/direct and src/play).
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    lateinit var status: TextView
    lateinit var progress: ProgressBar
    private lateinit var retry: View
    private var updateBox: View? = null
    private var leaving = false

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // created with the activity: it may register an activity-result launcher
    private val gate = UpdateGate(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(Ui.BRAND, Ui.BRAND, lightNavIcons = false)
        val root = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BRAND)
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }
        root.addView(FrameLayout(this).apply {
            background = rounded(Color.WHITE, dpf(20f))
            elevation = dpf(6f)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.avip_solar_logo)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "AVIP Solar"
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }, lp(MATCH, WRAP))
        root.addView(View(this), lp(MATCH, 0, 1f))
        val brand = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("मेरा Solar", 30f, Color.WHITE, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
            addView(text("Progress · Documents · Refer & Earn · Support", 14f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            addView(text("Energy for Future", 13f, Ui.SUN, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 4))
        }
        root.addView(brand)
        root.addView(View(this), lp(MATCH, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            max = 100
        }
        root.addView(progress, lp(dp(200), WRAP))
        status = text("Updates check हो रहे हैं…", 13f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }
        root.addView(status, lp(WRAP, WRAP).margins(this, t = 10))
        retry = pillButton("Retry", Ui.SUN, Ui.TEXT) { start() }.apply { visibility = View.GONE }
        root.addView(retry, lp(dp(160), WRAP).margins(this, t = 16))
        root.addView(text("v" + AppInfo.currentVersionName(this), 11f, 0x99FFFFFF.toInt()), lp(WRAP, WRAP).margins(this, t = 16))
        setContentView(root)

        brand.alpha = 0f
        brand.animate().alpha(1f).setDuration(300).start()

        gate.onCreate()

        val versionCode = AppInfo.currentVersionCode(this)
        val lastSeen = prefs.getLong("last_version_code", 0L)
        if (lastSeen in 1 until versionCode) {
            Toast.makeText(this, "Version ${AppInfo.currentVersionName(this)} पर update हो गया", Toast.LENGTH_LONG).show()
        }
        prefs.edit().putLong("last_version_code", versionCode).apply()

        start()
    }

    private fun start() {
        retry.visibility = View.GONE
        updateBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        status.text = "Updates check हो रहे हैं…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        lifecycleScope.launch {
            val minimum = async { delay(700) }
            val config = async { withContext(Dispatchers.IO) { Api.refreshConfig(this@SplashActivity) } }

            // updates are compulsory: if one is needed, the gate takes over this screen
            if (gate.updateNeeded()) return@launch

            val ready = config.await()
            minimum.await()
            if (!ready) {
                progress.visibility = View.GONE
                // offline: still open with the last saved data, if any
                if (Session.loggedIn(this@SplashActivity) && Store.load(this@SplashActivity) != null) { goNext(); return@launch }
                status.text = "Internet नहीं है। Connection check करके Retry दबाएँ।"
                retry.visibility = View.VISIBLE
                return@launch
            }
            goNext()
        }
    }

    override fun onResume() {
        super.onResume()
        gate.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        gate.onDestroy()
    }

    /** Update needed but not installed: the app stays on this screen until it is updated. */
    fun blockForUpdate(message: String, onUpdate: () -> Unit, linkText: String, onLink: () -> Unit) {
        if (isFinishing) return
        progress.visibility = View.GONE
        status.text = message
        retry.visibility = View.GONE
        updateBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val box = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pillButton("Update now", Ui.SUN, Ui.TEXT) {
                progress.visibility = View.VISIBLE
                (updateBox?.parent as? ViewGroup)?.removeView(updateBox)
                onUpdate()
            }, lp(dp(200), WRAP))
            addView(text(linkText, 13f, Color.WHITE, bold = true) {
                setPadding(dp(10), dp(12), dp(10), dp(6))
                setOnClickListener { onLink() }
            })
        }
        updateBox = box
        (status.parent as ViewGroup).let { p -> p.addView(box, p.indexOfChild(status) + 1, lp(WRAP, WRAP).margins(this, t = 16)) }
    }

    fun goNext() {
        if (leaving || isFinishing) return
        leaving = true
        startActivity(Intent(this, if (Session.loggedIn(this)) MainActivity::class.java else LoginActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
