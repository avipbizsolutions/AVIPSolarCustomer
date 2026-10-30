package com.avip.solarcustomer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The customer's app: four tabs — Home (progress), Documents, Refer & Earn and
 * Support. Everything comes from one "home" call and is kept on the phone, so
 * the app opens instantly and still shows the last data without signal.
 */
class MainActivity : AppCompatActivity() {

    private var home: Home? = null
    private var tab = TAB_HOME
    private var systemIndex = 0
    private var lastFetch = 0L
    private var loading = false
    private var loadError = ""
    private var uploadTitle = ""

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var picker: PhotoPicker

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val childScreen =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) refresh(false, force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.loggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        picker = PhotoPicker(this) { p -> upload(p) }
        tab = savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab", TAB_HOME)
        systemIndex = savedInstanceState?.getInt("system") ?: 0

        edgeToEdge(Ui.BRAND_DARK, Color.WHITE, lightNavIcons = true)
        content = FrameLayout(this)
        nav = horizontal {
            setBackgroundColor(Color.WHITE)
            elevation = dpf(10f)
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        setContentView(vertical {
            setBackgroundColor(Ui.BG)
            addView(content, lp(MATCH, 0, 1f))
            addView(nav, lp())
        })

        home = Store.load(this)
        lastFetch = Store.savedAt(this)
        render()
        Watcher.schedule(this)
        askNotifications()
        refresh(false, force = true)
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastFetch > 60_000) refresh(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("system", systemIndex)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotif", false)) return
        prefs.edit().putBoolean("askedNotif", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ data

    private fun refresh(showBusy: Boolean, force: Boolean = false) {
        if (loading && !force) return
        loading = true
        if (showBusy) {
            busy("Update ले रहे हैं…", { Store.refresh(this) }) { (h, _) ->
                loading = false; loadError = ""; home = h; lastFetch = System.currentTimeMillis(); render()
            }
            loading = false
            return
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Store.refresh(this@MainActivity) } }
            loading = false
            r.onSuccess { (h, _) ->
                loadError = ""; home = h; lastFetch = System.currentTimeMillis(); render()
            }.onFailure { e ->
                if (e is ApiException && e.sessionExpired) { showError(e); return@onFailure }
                loadError = e.message ?: "Update नहीं मिला"
                if (home == null) render() else toast(loadError)
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        val h = home
        val v = when {
            h == null -> emptyState()
            tab == TAB_DOCS -> docsTab(h)
            tab == TAB_REFER -> referTab(h)
            tab == TAB_SUPPORT -> supportTab(h)
            else -> homeTab(h)
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        paintNav()
    }

    private fun emptyState(): View = vertical {
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(32), dp(32), dp(32))
        if (loadError.isBlank()) {
            addView(android.widget.ProgressBar(context), lp(dp(44), dp(44)))
            addView(text("आपकी जानकारी आ रही है…", 15f, Ui.MUTED) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 14))
        } else {
            addView(text(loadError, 15f, Ui.TEXT) { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
            addView(pillButton("फिर कोशिश करें", Ui.BRAND) { loadError = ""; render(); refresh(false, force = true) }, lp(dp(220), WRAP).margins(context, t = 16))
        }
    }

    private fun paintNav() {
        nav.removeAllViews()
        listOf(
            Triple(TAB_HOME, "Home", R.drawable.ic_home),
            Triple(TAB_DOCS, "Documents", R.drawable.ic_doc),
            Triple(TAB_REFER, "Refer & Earn", R.drawable.ic_gift),
            Triple(TAB_SUPPORT, "Support", R.drawable.ic_headset),
        ).forEach { (id, label, res) ->
            val on = id == tab
            val color = if (on) Ui.BRAND else Ui.MUTED
            nav.addView(vertical {
                gravity = Gravity.CENTER
                minimumHeight = dp(52)
                isClickable = true
                foreground = context.ripple()
                contentDescription = label
                addView(FrameLayout(context).apply {
                    if (on) background = rounded(Ui.BRAND_LIGHT, dpf(14f))
                    setPadding(dp(14), dp(3), dp(14), dp(3))
                    addView(icon(res, color, 22))
                })
                addView(text(label, 11.5f, color, bold = on) { maxLines = 1 }, lp(WRAP, WRAP).margins(context, t = 2))
                setOnClickListener { if (tab != id) { tab = id; render() } }
            }, lp(0, WRAP, 1f))
        }
    }

    private fun scroll(body: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Ui.BG)
        addView(body)
    }

    private fun tabScreen(title: String, subtitle: String, body: LinearLayout): View = vertical {
        addView(topBar(title, subtitle, showBack = false, right = listOf(barButton(R.drawable.ic_refresh) { refresh(true) })))
        addView(scroll(body), lp(MATCH, 0, 1f))
    }

    private fun currentSystem(h: Home): SystemInfo? {
        if (h.systems.isEmpty()) return null
        if (systemIndex !in h.systems.indices) systemIndex = 0
        return h.systems[systemIndex]
    }

    // ------------------------------------------------------------------ Home

    private fun homeTab(h: Home): View {
        val sys = currentSystem(h)
        val body = vertical { setPadding(0, 0, 0, dp(16)) }

        // green header with greeting and overall progress
        val head = vertical {
            background = GradientDrawable().apply {
                setColor(Ui.BRAND)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(26f), dpf(26f), dpf(26f), dpf(26f))
            }
            setPadding(dp(18), dp(18), dp(10), dp(20))
            addView(horizontal {
                addView(vertical {
                    addView(text("नमस्ते,", 14f, 0xFFCFE3D7.toInt()))
                    addView(text("${h.name} जी", 22f, Color.WHITE, bold = true) { maxLines = 1 })
                }, lp(0, WRAP, 1f))
                addView(barButton(R.drawable.ic_refresh) { refresh(true) })
                addView(barButton(R.drawable.ic_person) { account(h) })
            })
            if (sys != null) addView(progressCard(sys), lp().margins(context, t = 14, r = 8))
        }
        body.addView(head)

        val inner = vertical(16)
        body.addView(inner)
        if (h.systems.size > 1) {
            inner.addView(text("आपके solar systems", 13f, Ui.MUTED, bold = true), lp().margins(this, b = 6))
            inner.addView(chips(h.systems.mapIndexed { i, s -> "${i + 1}. ${s.label}" }, "${systemIndex + 1}. ${sys?.label}") { pick ->
                val i = pick.substringBefore('.').toIntOrNull()?.minus(1) ?: run { render(); return@chips }
                if (i in h.systems.indices && i != systemIndex) { systemIndex = i; render() }
            }, lp().margins(this, b = 12))
        }
        if (h.announcement.isNotBlank()) inner.addView(announcement(h.announcement))
        if (sys == null) {
            inner.addView(note("आपकी installation की जानकारी जल्द ही यहाँ दिखेगी। कोई सवाल हो तो office को call करें।"))
        } else {
            if (sys.note.isNotBlank()) inner.addView(note(sys.note))
            if (sys.allDone) inner.addView(doneCard(h))
            inner.addView(timeline(sys))
        }
        inner.addView(teamCard(h, sys))
        if (loadError.isNotBlank()) inner.addView(text("Offline: पिछली बार की जानकारी दिख रही है।", 12.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp())
        return scroll(body)
    }

    private fun progressCard(sys: SystemInfo): View = card(14) {
        val total = sys.stages.size.coerceAtLeast(1)
        val done = sys.stages.count { it.done }
        addView(horizontal {
            addView(text(sys.label, 14f, Ui.MUTED) { maxLines = 1 }, lp(0, WRAP, 1f))
            addView(text("$done / $total", 14f, Ui.BRAND, bold = true))
        })
        addView(horizontal {
            background = rounded(0xFFE3E8E4.toInt(), dpf(4f))
            if (done > 0) addView(View(context).apply { background = rounded(Ui.BRAND, dpf(4f)) }, lp(0, dp(8), done.toFloat()))
            if (total - done > 0) addView(View(context), lp(0, dp(8), (total - done).toFloat()))
        }, lp(MATCH, dp(8)).margins(context, t = 10, b = 10))
        val cur = sys.currentStage
        addView(text(if (cur == null || sys.allDone) "सब काम पूरा ✓" else "अभी: ${cur.title}", 15.5f, Ui.TEXT, bold = true))
    }

    private fun announcement(msg: String): View = horizontal {
        background = rounded(Ui.SUN_LIGHT, dpf(14f), 0xFFF2D79A.toInt(), dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        gravity = Gravity.TOP
        addView(icon(R.drawable.ic_bell, Ui.SUN_TEXT, 22), lp(dp(22), dp(22)).margins(context, r = 10))
        addView(text(msg, 14f, Ui.SUN_TEXT) { setLineSpacing(0f, 1.15f) }, lp(0, WRAP, 1f))
    }.also { it.layoutParams = lp().margins(this, b = 12) }

    private fun doneCard(h: Home): View = card(16) {
        background = rounded(Ui.BRAND_LIGHT, dpf(16f))
        addView(text("🎉 आपका solar system पूरी तरह तैयार है!", 16f, Ui.BRAND_DARK, bold = true))
        addView(text("दोस्तों और रिश्तेदारों को AVIP Solar के बारे में बताएँ और हर kW पर ${Fmt.money(h.topRate)} तक कमाएँ।", 14f, Ui.TEXT), lp().margins(context, t = 4))
        addView(pillButton("Refer & Earn", Ui.BRAND, iconRes = R.drawable.ic_gift, heightDp = 46) { tab = TAB_REFER; render() }, lp(WRAP, WRAP).margins(context, t = 10))
    }.also { it.layoutParams = lp().margins(this, b = 12) }

    private fun timeline(sys: SystemInfo): View = card(16) {
        addView(text("Installation progress", 16f, Ui.TEXT, bold = true), lp().margins(context, b = 10))
        sys.stages.forEachIndexed { i, st ->
            val last = i == sys.stages.size - 1
            addView(horizontal {
                gravity = Gravity.TOP
                // dot + line
                addView(vertical {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(dot(st), lp(dp(26), dp(26)))
                    if (!last) addView(View(context).apply {
                        setBackgroundColor(if (st.done && sys.stages[i + 1].done) Ui.BRAND else 0xFFC9D2CC.toInt())
                        minimumHeight = dp(18)
                    }, lp(dp(2), 0, 1f))
                }, lp(dp(26), MATCH))
                addView(stageText(sys, st), lp(0, WRAP, 1f).margins(context, l = 12, b = if (last) 0 else 14))
            }, lp())
        }
    }.also { it.layoutParams = lp().margins(this, b = 12) }

    private fun dot(st: Stage): View {
        val f = FrameLayout(this)
        f.background = when {
            st.done -> circle(Ui.BRAND)
            st.current -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SUN); setStroke(dp(4), 0xFFFFF0CC.toInt()) }
            else -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), 0xFFA3AEA7.toInt()) }
        }
        if (st.done) f.addView(icon(R.drawable.ic_check, Color.WHITE, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        return f
    }

    private fun stageText(sys: SystemInfo, st: Stage): View = vertical {
        addView(text(st.title, 15f, if (st.done || st.current) Ui.TEXT else Ui.MUTED, bold = st.done || st.current))
        when {
            st.done -> if (st.date.isNotBlank()) addView(text(st.date, 13f, Ui.MUTED))
            st.current -> addView(text(hint(st.key), 13.5f, Ui.MUTED) { setLineSpacing(0f, 1.1f) }, lp().margins(context, t = 2))
        }
        if (st.current && sys.action.isNotBlank()) {
            addView(vertical {
                background = rounded(Ui.SUN_LIGHT, dpf(12f), 0xFFF2D79A.toInt(), dp(1))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(text("आपका काम: ${sys.action}", 14f, Ui.SUN_TEXT, bold = true) { setLineSpacing(0f, 1.1f) })
                addView(text("Photo upload करें", 14f, Ui.SUN_TEXT, bold = true) {
                    setPadding(0, dp(10), 0, dp(2))
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { uploadTitle = sys.action; picker.pick("Upload: ${sys.action}") }
                })
            }, lp().margins(context, t = 8))
        }
        if (st.key == "dispatch" && st.done && sys.serials.isNotEmpty()) {
            addView(link("Serial numbers देखें (${sys.serials.size})") { serials(sys) })
        }
        if (st.key == "install" && st.done && sys.photos > 0) {
            addView(text("📷 ${sys.photos} GPS photos AVIP office में saved", 13f, Ui.MUTED), lp().margins(context, t = 2))
        }
    }

    private fun link(label: String, onClick: () -> Unit): TextView = text(label, 13.5f, Ui.BRAND, bold = true) {
        setPadding(0, dp(6), 0, dp(4))
        setOnClickListener { onClick() }
    }

    private fun hint(key: String) = when (key) {
        "survey" -> "हमारी team आपके घर आकर छत का survey करेगी।"
        "order" -> "Quotation final होते ही order confirm होगा।"
        "portal" -> "हम PM Surya Ghar portal पर registration और DISCOM से feasibility approval करवा रहे हैं।"
        "dispatch" -> "Approval के बाद panel, inverter और structure आपके घर भेजे जाएँगे।"
        "install" -> "हमारी installation team structure, panels, wiring और earthing का काम पूरा करेगी।"
        "netmeter" -> "Net meter के लिए DISCOM में application दी जा रही है।"
        "commission" -> "DISCOM inspection के बाद net meter लगेगा और system चालू होगा।"
        "subsidy" -> "Commissioning के बाद subsidy सीधे आपके bank account में आती है।"
        else -> "काम चल रहा है।"
    }

    private fun serials(sys: SystemInfo) {
        val box = vertical(18) {
            sys.serials.forEach { s ->
                addView(vertical {
                    addView(text(listOf(s.type, s.make).filter { it.isNotBlank() }.joinToString(" · "), 13f, Ui.MUTED))
                    addView(text(s.serial, 16f, Ui.TEXT, bold = true) { setTextIsSelectable(true) })
                }, lp().margins(context, b = 12))
            }
            addView(text("Warranty claim के लिए ये serial numbers काम आएँगे।", 12.5f, Ui.MUTED))
        }
        AlertDialog.Builder(this).setTitle("Serial numbers").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("OK", null).show()
    }

    private fun teamCard(h: Home, sys: SystemInfo?): View = section("आपकी AVIP team") {
        if (sys != null && sys.salesExecutive.isNotBlank()) {
            addView(contactRow(sys.salesExecutive, "Sales executive", sys.salesExecMobile, showWhatsapp = false))
        }
        addView(contactRow("AVIP Solar office", Fmt.mobile(h.supportPhone), h.supportPhone, showWhatsapp = true, waNumber = h.supportWhatsapp))
    }

    private fun contactRow(name: String, sub: String, phone: String, showWhatsapp: Boolean, waNumber: String = phone): View = horizontal {
        setPadding(0, dp(6), 0, dp(6))
        addView(badge(R.drawable.ic_person, Ui.BRAND, 40), lp(dp(40), dp(40)))
        addView(vertical {
            addView(text(name, 15f, Ui.TEXT, bold = true) { maxLines = 1 })
            addView(text(sub, 13f, Ui.MUTED) { maxLines = 1 })
        }, lp(0, WRAP, 1f).margins(context, l = 12))
        if (showWhatsapp) addView(roundAction(R.drawable.ic_chat, "WhatsApp") {
            whatsapp(waNumber, "नमस्ते AVIP Solar, मैं ${home?.name ?: ""} (${Session.mobile(this@MainActivity)}) बोल रहा/रही हूँ।")
        })
        if (phone.filter { it.isDigit() }.length >= 10) addView(roundAction(R.drawable.ic_call, "Call") { dial(phone) })
    }

    private fun roundAction(res: Int, label: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = circle(Ui.BRAND_LIGHT)
        contentDescription = label
        isClickable = true
        foreground = ripple()
        addView(icon(res, Ui.BRAND, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).margins(context, l = 8)
    }

    private fun account(h: Home) {
        AlertDialog.Builder(this)
            .setTitle(h.name)
            .setMessage("Mobile: ${Fmt.mobile(h.mobile)}\nReferral code: ${h.code}\nApp version: ${AppInfo.currentVersionName(this)}")
            .setPositiveButton("OK", null)
            .setNegativeButton("Logout") { _, _ ->
                confirm("Logout?", "दोबारा login के लिए mobile number और PIN लगेगा।", "Logout") { signOut() }
            }
            .setNeutralButton("Privacy") { _, _ -> privacy() }
            .show()
    }

    private fun signOut() {
        Watcher.cancel(this)
        Session.clear(this)
        Store.clear(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /** Privacy policy and account deletion (Google Play requires both inside the app). */
    private fun privacy() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & Account")
            .setItems(arrayOf("Privacy Policy पढ़ें", "मेरा account delete करें")) { _, which ->
                if (which == 0) openUrl(AppInfo.PRIVACY_URL) else deleteAccount()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun deleteAccount() {
        confirm(
            "Account delete करें?",
            "आपका AVIP Solar app account तुरंत बंद हो जाएगा और आप login नहीं कर पाएँगे। " +
                "आपकी app details, photos, tickets और referral details 30 दिन में delete कर दी जाएँगी। " +
                "Warranty, subsidy और bill के records कानून के अनुसार AVIP के पास रहेंगे।\n\n" +
                "बाकी referral reward भी बंद हो जाएगा।",
            "Delete करें",
        ) {
            busy("Request भेज रहे हैं…", { Api.call(this, "deleteAccount") }) { o ->
                message("Account delete", o.optString("message").ifBlank { "आपका account बंद कर दिया गया है।" }) {
                    if (!o.optBoolean("demo")) signOut()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Documents

    private fun docsTab(h: Home): View {
        val body = vertical(16)
        if (h.documents.isEmpty()) {
            body.addView(note("अभी कोई document नहीं है। Quotation, Installation Report और warranty cards यहाँ आएँगे।"))
        }
        h.documents.forEach { d ->
            body.addView(card(14) {
                isClickable = true
                foreground = context.ripple()
                addView(horizontal {
                    addView(badge(if (d.title.contains("photo", true) || d.byCustomer) R.drawable.ic_camera else R.drawable.ic_pdf,
                        if (d.byCustomer) Ui.MUTED else Ui.BRAND, 44, square = true), lp(dp(44), dp(44)))
                    addView(vertical {
                        addView(text(d.title, 15.5f, Ui.TEXT, bold = true))
                        val sub = listOf(d.date, if (d.byCustomer) "आपने भेजा" else "").filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) addView(text(sub, 13f, Ui.MUTED))
                    }, lp(0, WRAP, 1f).margins(context, l = 12))
                    addView(text("खोलें", 14f, Ui.BRAND, bold = true))
                })
                setOnClickListener { openDoc(d) }
            }, lp().margins(this, b = 10))
        }
        body.addView(pillButton("Document / photo भेजें", Color.WHITE, Ui.BRAND, iconRes = R.drawable.ic_upload) {
            askText("क्या भेज रहे हैं?", "जैसे: बिजली bill, Aadhaar, bank passbook") { t -> uploadTitle = t; picker.pick("Photo: $t") }
        }.apply { background = rounded(Color.WHITE, dpf(25f), Ui.BRAND, dp(2)) }, lp().margins(this, t = 8))
        body.addView(text("Office को कोई document चाहिए तो उसकी photo यहाँ से भेजें।", 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 8))
        return tabScreen("Documents", "Quotation, report, warranty", body)
    }

    private fun openDoc(d: Doc) {
        if (d.id.isBlank()) { openUrl(d.url); return }
        busy("${d.title} खुल रहा है…", {
            Api.call(this, "doc", JSONObject().put("fileId", d.id), timeoutMs = 120000)
        }) { o -> openDocument(o.optString("name").ifBlank { d.title }, o.optString("mime"), o.optString("data")) }
    }

    private fun upload(p: Picked) {
        val title = uploadTitle.ifBlank { "Photo" }
        busy("Upload हो रहा है…", {
            Api.call(this, "upload", JSONObject().put("title", title).put("mime", "image/jpeg").put("data", p.base64), timeoutMs = 120000)
        }) {
            message("भेज दिया ✓", "\"$title\" AVIP office को मिल गया। धन्यवाद!")
            refresh(false, force = true)
        }
    }

    // ------------------------------------------------------------------ Refer & Earn

    private fun referTab(h: Home): View {
        val body = vertical(16)
        body.addView(card(18) {
            background = rounded(Ui.SUN, dpf(20f))
            elevation = 0f
            addView(text("हर kW पर कमाएँ", 15f, 0xFF3D2A00.toInt(), bold = true))
            addView(text("${Fmt.money(h.topRate)} तक", 36f, Ui.TEXT, bold = true))
            if (h.slabs.isNotEmpty()) {
                h.slabs.chunked(4).forEach { row ->
                    addView(horizontal {
                        row.forEach { s ->
                            addView(vertical {
                                gravity = Gravity.CENTER
                                background = rounded(0xFFFFE3A3.toInt(), dpf(10f))
                                setPadding(dp(4), dp(6), dp(4), dp(6))
                                addView(text(s.label, 11.5f, 0xFF3D2A00.toInt()) { maxLines = 1 })
                                addView(text(Fmt.money(s.rate), 14.5f, Ui.TEXT, bold = true))
                            }, lp(0, WRAP, 1f).margins(context, r = 6))
                        }
                        repeat(4 - row.size) { addView(View(context), lp(0, 1, 1f).margins(context, r = 6)) }
                    }, lp().margins(context, t = 8))
                }
                addView(text("जैसे 3 kW = ${Fmt.money(h.reward(3.0))} · 8 kW = ${Fmt.money(h.reward(8.0))} · installation के बाद UPI पर", 13f, 0xFF3D2A00.toInt()), lp().margins(context, t = 8))
            }
            addView(horizontal {
                background = rounded(Color.WHITE, dpf(12f))
                setPadding(dp(12), dp(8), dp(6), dp(8))
                addView(vertical {
                    addView(text("आपका referral code", 12f, Ui.MUTED))
                    addView(text(h.code.ifBlank { "—" }, 20f, Ui.TEXT, bold = true) { letterSpacing = 0.08f })
                }, lp(0, WRAP, 1f))
                addView(roundAction(R.drawable.ic_copy, "Copy code") { copy(h.code) })
            }, lp().margins(context, t = 12))
            addView(horizontal {
                addView(pillButton("WhatsApp पर भेजें", Ui.BRAND, heightDp = 48) { shareText(referralMessage(h)) }, lp(0, WRAP, 1f).margins(context, r = 6))
                addView(pillButton("Details भरें", Color.TRANSPARENT, Ui.TEXT, heightDp = 48) {
                    childScreen.launch(Intent(this@MainActivity, ReferActivity::class.java))
                }.apply { background = rounded(Color.TRANSPARENT, dpf(24f), Ui.TEXT, dp(2)) }, lp(0, WRAP, 1f).margins(context, l = 6))
            }, lp().margins(context, t = 12))
        }, lp().margins(this, b = 12))

        body.addView(horizontal {
            addView(stat("Referrals", h.referrals.size.toString(), Ui.TEXT), lp(0, WRAP, 1f).margins(context, r = 6))
            addView(stat("मिला", Fmt.money(h.refPaid), Ui.BRAND), lp(0, WRAP, 1f).margins(context, l = 3, r = 3))
            addView(stat("आने वाला", Fmt.money(h.refPending), Ui.SUN_TEXT), lp(0, WRAP, 1f).margins(context, l = 6))
        }, lp().margins(this, b = 12))

        body.addView(card(14) {
            addView(horizontal {
                addView(vertical {
                    addView(text("Reward किस UPI ID पर?", 13f, Ui.MUTED))
                    addView(text(h.upi.ifBlank { "अभी नहीं डाला" }, 16f, if (h.upi.isBlank()) Ui.DANGER else Ui.TEXT, bold = true))
                }, lp(0, WRAP, 1f))
                addView(text(if (h.upi.isBlank()) "डालें" else "बदलें", 14.5f, Ui.BRAND, bold = true) {
                    setPadding(dp(12), dp(10), dp(4), dp(10))
                    setOnClickListener { editUpi(h) }
                })
            })
        }, lp().margins(this, b = 14))

        body.addView(text("आपके referrals", 17f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        if (h.referrals.isEmpty()) body.addView(note("अभी कोई referral नहीं। ऊपर से अपने दोस्त या रिश्तेदार को refer करें।"))
        h.referrals.forEach { r ->
            body.addView(card(14) {
                addView(horizontal {
                    addView(vertical {
                        addView(text(r.name, 15.5f, Ui.TEXT, bold = true) { maxLines = 1 })
                        val sub = listOf(r.district, if (r.kw > 0) Fmt.kw(r.kw) else "", r.date).filter { it.isNotBlank() }.joinToString(" · ")
                        addView(text(sub, 13f, Ui.MUTED) { maxLines = 1 })
                    }, lp(0, WRAP, 1f))
                    addView(vertical {
                        gravity = Gravity.END
                        addView(referralPill(r.status, r.payout))
                        val amount = when {
                            r.reward <= 0 -> "—"
                            r.payout == "Paid" -> Fmt.money(r.reward) + (if (r.paidOn.isNotBlank()) " · ${r.paidOn}" else "")
                            else -> Fmt.money(r.reward) + " locked"
                        }
                        addView(text(amount, 13.5f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 4))
                    }, lp(WRAP, WRAP).margins(context, l = 8))
                })
            }, lp().margins(this, b = 8))
        }
        if (h.referralTerms.isNotBlank()) body.addView(text("* " + h.referralTerms, 12.5f, Ui.MUTED) { setLineSpacing(0f, 1.15f) }, lp().margins(this, t = 8))
        return tabScreen("Refer & Earn", "दोस्त या रिश्तेदार को solar लगवाने में मदद करें", body)
    }

    private fun stat(label: String, value: String, color: Int): View = card(12) {
        addView(text(label, 12f, Ui.MUTED))
        addView(text(value, 18f, color, bold = true) { maxLines = 1 })
    }

    private fun copy(code: String) {
        if (code.isBlank()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("AVIP referral code", code))
        toast("Code copy हो गया")
    }

    private fun referralMessage(h: Home): String {
        val wa = "https://wa.me/91${h.supportWhatsapp}?text=" +
            android.net.Uri.encode("नमस्ते AVIP Solar, मुझे घर पर solar लगवाना है। Referral code: ${h.code}")
        return "नमस्ते! 🙏 मैंने AVIP Solar से घर पर solar लगवाया है — बिजली bill में अच्छी बचत और PM Surya Ghar subsidy भी।\n\n" +
            "आप भी लगवाना चाहें तो AVIP Solar से बात करें और मेरा referral code *${h.code}* बताएँ:\n" +
            "📞 ${Fmt.mobile(h.supportPhone)}\n💬 $wa"
    }

    private fun editUpi(h: Home) {
        askText("UPI ID", "जैसे 9876543210@ybl", h.upi, required = false) { v ->
            busy("Save हो रहा है…", { Api.call(this, "setUpi", JSONObject().put("upi", v)) }) {
                toast("UPI ID save हो गया"); refresh(false, force = true)
            }
        }
    }

    // ------------------------------------------------------------------ Support

    private fun supportTab(h: Home): View {
        val body = vertical(16)
        body.addView(pillButton("नई शिकायत दर्ज करें", Ui.BRAND, iconRes = R.drawable.ic_plus, heightDp = 56) {
            childScreen.launch(Intent(this, TicketActivity::class.java))
        }, lp().margins(this, b = 12))
        body.addView(horizontal {
            addView(pillButton("Call करें", Color.WHITE, Ui.BRAND, iconRes = R.drawable.ic_call, heightDp = 48) { dial(h.supportPhone) }
                .apply { background = rounded(Color.WHITE, dpf(24f), Ui.BRAND, dp(2)) }, lp(0, WRAP, 1f).margins(context, r = 6))
            addView(pillButton("WhatsApp", Color.WHITE, Ui.BRAND, iconRes = R.drawable.ic_chat, heightDp = 48) {
                whatsapp(h.supportWhatsapp, "नमस्ते AVIP Solar, मैं ${h.name} (${Fmt.mobile(h.mobile)}) बोल रहा/रही हूँ।")
            }.apply { background = rounded(Color.WHITE, dpf(24f), Ui.BRAND, dp(2)) }, lp(0, WRAP, 1f).margins(context, l = 6))
        }, lp().margins(this, b = 16))

        body.addView(text("आपके tickets", 17f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        if (h.tickets.isEmpty()) body.addView(note("कोई ticket नहीं। Solar में कोई भी समस्या हो तो ऊपर से शिकायत दर्ज करें — हमारी team जल्दी संपर्क करेगी।"))
        h.tickets.forEach { t ->
            body.addView(card(14) {
                isClickable = true
                foreground = context.ripple()
                addView(horizontal {
                    addView(text("#${t.id} · ${t.category}", 15f, Ui.TEXT, bold = true) { maxLines = 1 }, lp(0, WRAP, 1f))
                    addView(ticketPill(t.status), lp(WRAP, WRAP).margins(context, l = 8))
                })
                val sub = when {
                    t.status == "Visit Scheduled" && t.visit.isNotBlank() -> "Visit: ${t.visit}" + (if (t.assigned.isNotBlank()) " · ${t.assigned}" else "")
                    t.status == "Resolved" -> "ठीक हो गया? Confirm करके rating दें"
                    t.assigned.isNotBlank() -> "Technician: ${t.assigned}"
                    else -> "दर्ज: ${t.date}"
                }
                addView(text(sub, 13.5f, if (t.status == "Resolved") Ui.BRAND else Ui.MUTED, bold = t.status == "Resolved"), lp().margins(context, t = 4))
                setOnClickListener { ticketDetails(t) }
            }, lp().margins(this, b = 8))
        }
        return tabScreen("Support", "शिकायत दर्ज करें, status देखें", body)
    }

    private fun ticketDetails(t: Ticket) {
        val box = vertical(20) {
            addView(ticketPill(t.status), lp(WRAP, WRAP).margins(context, b = 10))
            addView(kv("दर्ज किया", t.date))
            if (t.errorCode.isNotBlank()) addView(kv("Error code", t.errorCode))
            if (t.assigned.isNotBlank()) addView(kv("Technician", t.assigned))
            if (t.visit.isNotBlank()) addView(kv("Visit", t.visit))
            if (t.photos > 0) addView(kv("Photos", t.photos.toString()))
            addView(text("समस्या", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 10))
            addView(text(t.description, 14.5f, Ui.TEXT) { setLineSpacing(0f, 1.15f) })
            if (t.resolution.isNotBlank()) {
                addView(text("क्या किया गया", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 10))
                addView(text(t.resolution, 14.5f, Ui.TEXT) { setLineSpacing(0f, 1.15f) })
            }
            if (t.rating > 0) addView(text("आपकी rating: " + "★".repeat(t.rating) + "☆".repeat(5 - t.rating), 15f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 10))
        }
        val b = AlertDialog.Builder(this).setTitle("Ticket #${t.id} · ${t.category}")
            .setView(ScrollView(this).apply { addView(box) })
        when (t.status) {
            "Resolved" -> b.setPositiveButton("ठीक हो गया ✓") { _, _ -> rate(t) }
                .setNegativeButton("अभी भी समस्या है") { _, _ -> reopen(t) }
                .setNeutralButton("बंद करें", null)
            "Closed" -> b.setPositiveButton("OK", null).setNegativeButton("फिर से खोलें") { _, _ -> reopen(t) }
            else -> b.setPositiveButton("OK", null)
        }
        b.show()
    }

    private fun rate(t: Ticket) {
        var stars = 5
        val starViews = mutableListOf<TextView>()
        val feedback = input("कुछ कहना चाहें? (optional)")
        val box = vertical(20) {
            addView(text("Service कैसी लगी?", 15f, Ui.TEXT, bold = true))
            addView(horizontal {
                (1..5).forEach { n ->
                    val tv = text("★", 36f, Ui.SUN) {
                        setPadding(dp(4), 0, dp(4), 0)
                        contentDescription = "$n star"
                        setOnClickListener {
                            stars = n
                            starViews.forEachIndexed { i, v -> v.setTextColor(if (i < n) Ui.SUN else 0xFFC9D2CC.toInt()) }
                        }
                    }
                    starViews += tv
                    addView(tv)
                }
            }, lp().margins(context, t = 6, b = 10))
            addView(feedback, lp())
        }
        AlertDialog.Builder(this).setTitle("Ticket #${t.id}").setView(box)
            .setPositiveButton("Submit") { _, _ ->
                busy("Save हो रहा है…", {
                    Api.call(this, "ticketClose", JSONObject().put("ticketId", t.id).put("rating", stars).put("feedback", feedback.text.toString().trim()))
                }) { toast("धन्यवाद! 🙏"); refresh(false, force = true) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun reopen(t: Ticket) {
        askText("क्या समस्या बची है?", "थोड़ा detail में लिखें", multiLine = true) { note ->
            busy("भेज रहे हैं…", {
                Api.call(this, "ticketReopen", JSONObject().put("ticketId", t.id).put("note", note))
            }) { toast("Ticket फिर से खोल दिया गया"); refresh(false, force = true) }
        }
    }

    companion object {
        const val TAB_HOME = 0
        const val TAB_DOCS = 1
        const val TAB_REFER = 2
        const val TAB_SUPPORT = 3
    }
}
