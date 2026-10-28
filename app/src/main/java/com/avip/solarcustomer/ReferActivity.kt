package com.avip.solarcustomer

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** The customer fills in a friend's or relative's details; the lead goes to the AVIP sales team. */
class ReferActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = Store.load(this)
        lateinit var send: () -> Unit
        val submit = pillButton("Referral भेजें", Ui.BRAND, heightDp = 54) { send() }
        val body = screen("दोस्त को refer करें", "Details भरें, बाकी AVIP team करेगी", bottom = submit)

        if (h != null && h.topRate > 0) {
            body.addView(note("जब आपके दोस्त का solar install हो जाएगा, आपको हर kW पर ${Fmt.money(h.topRate)} तक मिलेंगे — जैसे 3 kW पर ${Fmt.money(h.reward(3.0))}, 5 kW पर ${Fmt.money(h.reward(5.0))}।", Ui.SUN_LIGHT, Ui.SUN_TEXT))
        }
        val name = field(body, "दोस्त / रिश्तेदार का नाम *", "", "पूरा नाम", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person) {}
        val mobile = field(body, "Mobile number *", "", "10 अंकों का number", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13) {}
        val district = field(body, "District / शहर", "", "जैसे Patna, Danapur, Bihta", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_location) {}
        val address = field(body, "पता (optional)", "", "मोहल्ला, गाँव", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_home) {}
        val noteBox = field(body, "कुछ और बताना हो (optional)", "", "जैसे: शाम को call करें, 2 मंज़िला घर", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit) {}
        body.addView(text("पहले अपने दोस्त से पूछ लें कि AVIP की team उन्हें call कर सकती है।", 13f, Ui.MUTED), lp().margins(this, t = 14))

        send = {
            val n = name.text.toString().trim()
            val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
            when {
                n.length < 2 -> { name.error = "नाम डालें"; name.requestFocus() }
                !Regex("^[6-9]\\d{9}$").matches(m) -> { mobile.error = "सही 10 अंकों का number डालें"; mobile.requestFocus() }
                else -> busy("भेज रहे हैं…", {
                    Api.call(this, "refer", JSONObject().put("name", n).put("mobile", m)
                        .put("district", district.text.toString().trim()).put("address", address.text.toString().trim())
                        .put("note", noteBox.text.toString().trim()))
                }) {
                    message("धन्यवाद! 🙏", "$n जी की details AVIP team को मिल गई हैं। हमारी team 24 घंटे में उन्हें call करेगी। Status आप Refer & Earn में देख सकते हैं।") {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
    }
}
