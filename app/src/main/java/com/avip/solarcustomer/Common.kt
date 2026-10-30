package com.avip.solarcustomer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [work] in the background with a small "please wait" box, then [done] on
 * the main thread. Errors are shown in plain words; an expired login goes back
 * to the login screen.
 */
fun <T> AppCompatActivity.busy(message: String, work: () -> T, done: (T) -> Unit) {
    val box = horizontal {
        setPadding(dp(22), dp(20), dp(22), dp(20))
        addView(ProgressBar(context), lp(dp(36), dp(36)))
        addView(text(message, 15f, Ui.TEXT), lp(WRAP, WRAP).margins(context, l = 16))
    }
    val dialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
    dialog.show()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { work() } }
        if (!isFinishing && !isDestroyed) try { dialog.dismiss() } catch (_: Exception) {}
        result.onSuccess { done(it) }.onFailure { showError(it) }
    }
}

fun Activity.showError(e: Throwable) {
    if (e is ApiException && e.sessionExpired) {
        Session.clear(this)
        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        return
    }
    if (isFinishing) return
    AlertDialog.Builder(this)
        .setTitle("नहीं हो सका")
        .setMessage(e.message ?: "कुछ गड़बड़ हो गई।")
        .setPositiveButton("OK", null)
        .show()
}

fun Activity.toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

fun Activity.message(title: String, text: String, onOk: (() -> Unit)? = null) {
    AlertDialog.Builder(this).setTitle(title).setMessage(text)
        .setPositiveButton("OK") { _, _ -> onOk?.invoke() }
        .setCancelable(onOk == null)
        .show()
}

fun Activity.confirm(title: String, message: String, yes: String = "हाँ", onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("नहीं", null)
        .show()
}

/** Asks for one line (or a few lines) of text. */
fun Activity.askText(
    title: String, hint: String, initial: String = "", required: Boolean = true,
    multiLine: Boolean = false, onOk: (String) -> Unit,
) {
    val e = input(hint, if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT, value = initial)
    if (multiLine) { e.minLines = 3; e.gravity = Gravity.TOP or Gravity.START }
    val box = vertical(16) { addView(e, lp()) }
    val d = AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK", null).setNegativeButton("Cancel", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = e.text.toString().trim()
            if (required && v.isBlank()) { e.error = "ज़रूरी है"; return@setOnClickListener }
            d.dismiss(); onOk(v)
        }
    }
    d.show()
}

fun Activity.openUrl(url: String) {
    if (url.isBlank()) return
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("नहीं खुल सका") }
}

fun Activity.dial(number: String) {
    val n = number.filter { it.isDigit() }
    if (n.isBlank()) return
    try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) } catch (_: Exception) {}
}

/** Opens a WhatsApp chat with [number] (10 digits, India) with [text] typed in. */
fun Activity.whatsapp(number: String, text: String) {
    val n = number.filter { it.isDigit() }.takeLast(10)
    openUrl("https://wa.me/91$n?text=" + Uri.encode(text))
}

/** Shares [text] — straight to WhatsApp when it is installed, else the normal share sheet. */
fun Activity.shareText(text: String) {
    val base = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            packageManager.getPackageInfo(pkg, 0)
            startActivity(Intent(base).setPackage(pkg))
            return
        } catch (_: Exception) {
        }
    }
    try { startActivity(Intent.createChooser(base, "Share")) } catch (_: Exception) { toast("Share नहीं हो सका") }
}

/** A coloured rounded label. */
fun Context.pill(label: String, bg: Int, fg: Int): TextView = text(label, 12.5f, fg, bold = true) {
    background = rounded(bg, dpf(14f))
    setPadding(dp(10), dp(4), dp(10), dp(4))
    maxLines = 1
}

fun Context.ticketPill(status: String): TextView = when (status) {
    "Resolved", "Closed" -> pill(status, Ui.GREEN_LIGHT, Ui.BRAND_DARK)
    "Visit Scheduled", "Assigned" -> pill(status, Ui.BLUE_LIGHT, Ui.BLUE)
    "Reopened" -> pill(status, Ui.DANGER_LIGHT, Ui.DANGER)
    else -> pill(status.ifBlank { "Open" }, Ui.SUN_LIGHT, Ui.SUN_TEXT)
}

fun Context.referralPill(status: String, payout: String): TextView = when {
    payout == "Paid" -> pill("Reward paid", Ui.GREEN_LIGHT, Ui.BRAND_DARK)
    status == "Installed" -> pill("Installed", Ui.GREEN_LIGHT, Ui.BRAND_DARK)
    status == "Order confirmed" -> pill("Order confirmed", Ui.SUN_LIGHT, Ui.SUN_TEXT)
    status == "Not interested" -> pill("Not interested", 0xFFECEEED.toInt(), Ui.MUTED)
    status == "Site visit done" || status == "Contacted" -> pill(status, Ui.BLUE_LIGHT, Ui.BLUE)
    else -> pill(status.ifBlank { "Lead received" }, 0xFFECEEED.toInt(), Ui.TEXT)
}

/** Standard screen: top bar, scrolling body, optional bottom action bar. Returns the body. */
fun Activity.screen(
    title: String, subtitle: String? = null, right: List<View> = emptyList(),
    bottom: View? = null, onBack: (() -> Unit)? = null,
): LinearLayout {
    val body = vertical(16)
    val root = vertical {
        setBackgroundColor(Ui.BG)
        addView(topBar(title, subtitle, right = right, onBack = onBack))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }, lp(MATCH, 0, 1f))
        if (bottom != null) addView(vertical {
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(14))
            elevation = dpf(8f)
            addView(bottom, lp())
        })
    }
    (this as? androidx.appcompat.app.AppCompatActivity)
        ?.edgeToEdge(Ui.BRAND_DARK, if (bottom != null) android.graphics.Color.WHITE else Ui.BG, lightNavIcons = true)
    setContentView(root)
    return body
}

/** White card with a bold heading. */
fun Context.section(title: String, subtitle: String? = null, block: LinearLayout.() -> Unit): View = card(16) {
    addView(text(title, 16f, Ui.TEXT, bold = true))
    if (!subtitle.isNullOrBlank()) addView(text(subtitle, 13f, Ui.MUTED), lp().margins(context, t = 2))
    addView(vertical { block() }, lp().margins(context, t = 8))
}.also { it.layoutParams = lp().margins(this, b = 12) }

/** Small coloured hint box. */
fun Context.note(message: String, bg: Int = Ui.BRAND_LIGHT, fg: Int = Ui.TEXT): View = text(message, 14f, fg) {
    background = rounded(bg, dpf(12f))
    setPadding(dp(12), dp(10), dp(12), dp(10))
    setLineSpacing(0f, 1.15f)
}.also { it.layoutParams = lp().margins(this, b = 12) }

/**
 * Grid of option buttons, single choice; wraps into [columns] per row
 * (instead of a sideways scroll, so every option is visible).
 */
fun Context.choiceGrid(options: List<String>, selected: String, columns: Int = 2, onPick: (String) -> Unit): View {
    val box = vertical { }
    val views = mutableListOf<Pair<TextView, String>>()
    var current = selected
    fun paint() = views.forEach { (tv, o) ->
        val on = o == current
        tv.background = if (on) rounded(Ui.BRAND, dpf(22f)) else rounded(android.graphics.Color.WHITE, dpf(22f), 0xFFC9D2CC.toInt(), dp(1))
        tv.setTextColor(if (on) android.graphics.Color.WHITE else Ui.TEXT)
    }
    options.chunked(columns).forEach { chunk ->
        val row = horizontal { }
        chunk.forEach { o ->
            val tv = text(o, 14f, Ui.TEXT, bold = true) {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isClickable = true
                setOnClickListener { current = o; paint(); onPick(o) }
            }
            views += tv to o
            row.addView(tv, lp(0, WRAP, 1f).margins(this, r = 8))
        }
        repeat(columns - chunk.size) { row.addView(View(this), lp(0, 1, 1f).margins(this, r = 8)) }
        box.addView(row, lp().margins(this, b = 8))
    }
    paint()
    return box
}
