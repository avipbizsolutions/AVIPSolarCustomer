package com.avip.solarcustomer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Login with mobile number + 4-digit PIN. The first PIN comes from the AVIP
 * office on WhatsApp (temporary); at first login the customer sets their own.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(Ui.BRAND, Ui.BG, lightNavIcons = true)
        if (Session.mustSetPin(this) && Session.token(this).isNotBlank()) showSetPin() else showLogin()
    }

    private fun frame(title: String, subtitle: String): LinearLayout {
        val head = vertical {
            setPadding(dp(28), dp(48), dp(28), dp(30))
            addView(android.widget.FrameLayout(context).apply {
                background = rounded(Color.WHITE, dpf(18f))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.avip_solar_logo_stacked)
                    adjustViewBounds = true
                    contentDescription = "AVIP Solar"
                }, android.widget.FrameLayout.LayoutParams(WRAP, dp(76)))
            }, lp(WRAP, WRAP))
            addView(text(title, 26f, Color.WHITE, bold = true) { setLineSpacing(0f, 1.1f) }, lp().margins(context, t = 22))
            addView(text(subtitle, 15f, 0xFFCFE3D7.toInt()) { setLineSpacing(0f, 1.15f) }, lp().margins(context, t = 6))
        }
        form = vertical(24) {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BG)
                cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f)
            }
        }
        val root = vertical {
            setBackgroundColor(Ui.BRAND)
            addView(head)
            addView(form, lp(MATCH, 0, 1f))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BG)
            addView(root)
        })
        return form
    }

    private fun showLogin() {
        val f = frame("नमस्ते!\nअपने solar का हाल देखें", "Progress, documents, referral और support — सब एक जगह।")
        val mobile = field(f, "Mobile number", Session.mobile(this), "10 अंकों का number", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13) {}
        val pin = field(f, "4 अंकों का PIN", "", "••••", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, R.drawable.ic_lock, 4) {}
        f.addView(text("PIN भूल गए?", 14f, Ui.BRAND, bold = true) {
            gravity = Gravity.END
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { forgot(mobile) }
        }, lp())
        f.addView(pillButton("Login करें", Ui.BRAND, Color.WHITE, heightDp = 56) {
            val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
            val p = pin.text.toString().trim()
            if (m.length != 10) { mobile.error = "10 अंकों का number डालें"; return@pillButton }
            if (p.length != 4) { pin.error = "4 अंकों का PIN"; return@pillButton }
            busy("Login हो रहा है…", {
                Api.call(this, "login", JSONObject().put("mobile", m).put("pin", p).put("token", ""))
            }) { o ->
                val must = o.optBoolean("mustSetPin")
                Session.save(this, o.optString("token"), o.optString("name"), m, must)
                if (must) showSetPin() else openHome()
            }
        }, lp().margins(this, t = 8))
        f.addView(note("पहली बार? AVIP office से WhatsApp पर मिला temporary PIN डालें। उसके बाद आप अपना नया PIN बनाएँगे।", Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, t = 18))
        f.addView(text("मदद चाहिए? Call करें 92881 76289", 14f, Ui.MUTED) {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { dial("9288176289") }
        }, lp())
        f.addView(text("Privacy Policy", 13f, Ui.MUTED) {
            gravity = Gravity.CENTER
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener { openUrl(AppInfo.PRIVACY_URL) }
        }, lp())
    }

    private fun forgot(mobile: EditText) {
        val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
        if (m.length != 10) { mobile.error = "पहले अपना 10 अंकों का number डालें"; mobile.requestFocus(); return }
        confirm("PIN भूल गए?", "AVIP office को नया PIN भेजने की request जाएगी। Office WhatsApp पर $m पर नया temporary PIN भेजेगा।", "Request भेजें") {
            busy("Request भेज रहे हैं…", {
                Api.call(this, "forgotPin", JSONObject().put("mobile", m).put("token", ""))
            }) { o -> message("Request भेज दी गई", o.optString("message")) }
        }
    }

    private fun showSetPin() {
        val f = frame("अपना नया PIN बनाएँ", "नमस्ते ${Session.name(this)} जी — पहली बार login पर office वाला PIN बदलना ज़रूरी है।")
        val numPass = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val pin1 = field(f, "नया 4 अंकों का PIN", "", "••••", numPass, R.drawable.ic_lock, 4) {}
        val pin2 = field(f, "PIN दोबारा डालें", "", "••••", numPass, R.drawable.ic_lock, 4) {}
        f.addView(vertical {
            listOf(
                "1234, 0000, 1111 जैसा आसान PIN नहीं चलेगा",
                "AVIP office भी आपका PIN नहीं देख सकता",
                "भूल गए तो login screen पर “PIN भूल गए?” दबाएँ",
            ).forEach { line ->
                addView(horizontal {
                    addView(text("✓", 14f, Ui.GREEN, bold = true), lp(WRAP, WRAP).margins(context, r = 8))
                    addView(text(line, 14f, Ui.TEXT), lp(0, WRAP, 1f))
                }, lp().margins(context, t = 6))
            }
        }, lp().margins(this, t = 16))
        f.addView(pillButton("PIN save करें", Ui.BRAND, Color.WHITE, heightDp = 56) {
            val a = pin1.text.toString().trim()
            val b = pin2.text.toString().trim()
            if (a.length != 4) { pin1.error = "4 अंक"; return@pillButton }
            if (a != b) { pin2.error = "दोनों PIN एक जैसे नहीं हैं"; return@pillButton }
            busy("PIN save हो रहा है…", {
                Api.call(this, "setPin", JSONObject().put("newPin", a))
            }) { o ->
                Session.save(this, o.optString("token"), o.optString("name"), Session.mobile(this), false)
                toast("नया PIN save हो गया")
                openHome()
            }
        }, lp().margins(this, t = 22))
        f.addView(text("Logout", 14f, Ui.MUTED, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { Session.clear(this@LoginActivity); showLogin() }
        }, lp())
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
