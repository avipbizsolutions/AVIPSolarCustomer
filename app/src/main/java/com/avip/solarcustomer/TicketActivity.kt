package com.avip.solarcustomer

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** New support ticket: type of problem, details, inverter error code and up to 6 photos. */
class TicketActivity : AppCompatActivity() {

    private var category = ""
    private var systemId = ""
    private val photos = mutableListOf<Picked>()
    private lateinit var photoRow: LinearLayout
    private lateinit var picker: PhotoPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker = PhotoPicker(this) { p -> photos += p; paintPhotos() }
        val h = Store.load(this)
        val systems = h?.systems ?: emptyList()
        systemId = systems.firstOrNull()?.id ?: ""
        val categories = h?.categories?.takeIf { it.isNotEmpty() }
            ?: listOf("Generation कम", "Inverter error", "Panel / structure", "Wiring / earthing", "Net meter", "Subsidy", "Billing", "Other")

        val submit = pillButton("Ticket भेजें", Ui.BRAND, heightDp = 54) { send() }
        val body = screen("शिकायत दर्ज करें", "Raise a ticket", bottom = submit)

        if (systems.size > 1) {
            body.addView(text("किस system में समस्या है?", 14f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
            body.addView(choiceGrid(systems.mapIndexed { i, s -> "${i + 1}. ${s.label}" }, "1. ${systems[0].label}", columns = 1) { pick ->
                val i = pick.substringBefore('.').toIntOrNull()?.minus(1) ?: 0
                systemId = systems.getOrNull(i)?.id ?: systemId
            }, lp().margins(this, b = 12))
        }

        body.addView(text("समस्या क्या है?", 14f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        body.addView(choiceGrid(categories, "", columns = 2) { category = it }, lp().margins(this, b = 8))

        val desc = field(body, "थोड़ा detail में बताएँ", "", "जैसे: 4 दिन से दोपहर में भी generation 6 unit से कम आ रहा है",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit) {}
        desc.minLines = 3
        desc.gravity = Gravity.TOP or Gravity.START
        val err = field(body, "Inverter error code (अगर दिखे)", "", "जैसे E-21", InputType.TYPE_CLASS_TEXT, R.drawable.ic_bolt, 40) {}

        body.addView(text("Photo / screenshot", 13f, Ui.MUTED, bold = true), lp().margins(this, t = 14, b = 6))
        photoRow = horizontal { }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(photoRow) }, lp())
        body.addView(note("Inverter screen, panel या wiring की photo भेजने से technician सही सामान लेकर आएगा।", Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, t = 12))
        paintPhotos()

        this.desc = desc
        this.err = err
    }

    private lateinit var desc: android.widget.EditText
    private lateinit var err: android.widget.EditText

    private fun paintPhotos() {
        photoRow.removeAllViews()
        photos.forEachIndexed { i, p ->
            photoRow.addView(FrameLayout(this).apply {
                background = rounded(Ui.LINE, dpf(12f))
                clipToOutline = true
                addView(ImageView(context).apply {
                    setImageBitmap(p.thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(icon(R.drawable.ic_close, Color.WHITE, 26).apply {
                    background = circle(0xAA000000.toInt())
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    contentDescription = "Photo हटाएँ"
                    setOnClickListener { photos.removeAt(i); paintPhotos() }
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            }, LinearLayout.LayoutParams(dp(96), dp(96)).margins(this, r = 10))
        }
        if (photos.size < 6) {
            photoRow.addView(vertical {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, dpf(12f), Ui.BRAND, dp(2))
                isClickable = true
                foreground = context.ripple()
                contentDescription = "Photo जोड़ें"
                addView(icon(R.drawable.ic_camera, Ui.BRAND, 26))
                addView(text("Photo जोड़ें", 13f, Ui.BRAND, bold = true), lp(WRAP, WRAP).margins(context, t = 4))
                setOnClickListener { picker.pick() }
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
    }

    private fun send() {
        val text = desc.text.toString().trim()
        if (category.isBlank()) { toast("समस्या का प्रकार चुनें"); return }
        if (text.length < 5) { desc.error = "थोड़ा detail में लिखें"; desc.requestFocus(); return }
        val all = photos.toList()
        busy(if (all.isEmpty()) "Ticket भेज रहे हैं…" else "Ticket और ${all.size} photo भेज रहे हैं…", {
            val o = Api.call(this, "ticket", JSONObject().put("category", category).put("description", text)
                .put("errorCode", err.text.toString().trim()).put("systemId", systemId))
            val id = o.optString("ticketId")
            var failed = 0
            all.forEach { p ->
                try {
                    Api.call(this, "ticketPhoto", JSONObject().put("ticketId", id).put("data", p.base64), timeoutMs = 120000)
                } catch (_: Exception) {
                    failed++
                }
            }
            id to failed
        }) { (id, failed) ->
            val extra = if (failed > 0) "\n\n$failed photo नहीं जा सकी — WhatsApp पर भेज दें।" else ""
            message("Ticket दर्ज हो गया ✓", "Ticket number: $id\nहमारी team जल्द ही आपसे संपर्क करेगी।$extra") {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
