package com.avip.solarcustomer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

data class Stage(val key: String, val title: String, val date: String, val done: Boolean, val current: Boolean)
data class Serial(val type: String, val make: String, val serial: String)

data class SystemInfo(
    val id: String, val kw: Double, val type: String, val address: String, val district: String,
    val stages: List<Stage>, val doneCount: Int, val action: String, val note: String,
    val serials: List<Serial>, val photos: Int, val salesExecutive: String, val salesExecMobile: String,
) {
    val currentStage: Stage? get() = stages.firstOrNull { it.current }
    val allDone: Boolean get() = stages.isNotEmpty() && stages.all { it.done }
    val label: String
        get() = listOf(if (kw > 0) Fmt.kw(kw) else "", type).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Solar system" }
}

data class Doc(val id: String, val url: String, val title: String, val date: String, val byCustomer: Boolean)

data class Referral(
    val id: String, val name: String, val district: String, val date: String, val status: String,
    val kw: Double, val reward: Long, val payout: String, val paidOn: String,
)

data class Slab(val from: Double, val to: Double, val rate: Long) {
    val label: String get() = if (to <= 0) "${Fmt.num(from)}+ kW" else "${Fmt.num(from)}–${Fmt.num(to)} kW"
}

data class Ticket(
    val id: String, val date: String, val category: String, val description: String, val errorCode: String,
    val photos: Int, val status: String, val assigned: String, val visit: String, val resolution: String, val rating: Int,
)

/** Everything the app shows, from the "home" call. */
data class Home(
    val name: String, val mobile: String, val code: String, val upi: String,
    val systems: List<SystemInfo>, val documents: List<Doc>,
    val referrals: List<Referral>, val refPaid: Long, val refPending: Long,
    val slabs: List<Slab>, val referralTerms: String,
    val tickets: List<Ticket>, val categories: List<String>,
    val announcement: String, val supportPhone: String, val supportWhatsapp: String,
) {
    /** Reward for a system of [kw]: each kW is paid at the rate of its own slab (same as the sheet). */
    fun reward(kw: Double): Long {
        var total = 0.0
        slabs.sortedBy { it.from }.forEach { s ->
            val hi = if (s.to <= 0) Double.MAX_VALUE else s.to
            if (kw > s.from) total += (minOf(kw, hi) - s.from) * s.rate
        }
        return Math.round(total)
    }

    val topRate: Long get() = slabs.minByOrNull { it.from }?.rate ?: 0

    companion object {
        private fun JSONObject.s(k: String) = optString(k, "").let { if (it == "null") "" else it }
        private fun <T> JSONArray?.map(f: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
        }

        fun parse(o: JSONObject): Home {
            val c = o.optJSONObject("customer") ?: JSONObject()
            val totals = o.optJSONObject("referralTotals") ?: JSONObject()
            val support = o.optJSONObject("support") ?: JSONObject()
            val cats = o.optJSONArray("categories")
            return Home(
                name = c.s("name"), mobile = c.s("mobile"), code = c.s("code"), upi = c.s("upi"),
                systems = o.optJSONArray("systems").map { x ->
                    SystemInfo(
                        id = x.s("id"), kw = x.optDouble("kw", 0.0), type = x.s("type"),
                        address = x.s("address"), district = x.s("district"),
                        stages = x.optJSONArray("stages").map { st ->
                            Stage(st.s("key"), st.s("title"), st.s("date"), st.optBoolean("done"), st.optBoolean("current"))
                        },
                        doneCount = x.optInt("doneCount"), action = x.s("action"), note = x.s("note"),
                        serials = x.optJSONArray("serials").map { sr -> Serial(sr.s("type"), sr.s("make"), sr.s("serial")) },
                        photos = x.optInt("photos"), salesExecutive = x.s("salesExecutive"), salesExecMobile = x.s("salesExecMobile"),
                    )
                },
                documents = o.optJSONArray("documents").map { d ->
                    Doc(d.s("id"), d.s("url"), d.s("title"), d.s("date"), d.s("by") == "customer")
                },
                referrals = o.optJSONArray("referrals").map { r ->
                    Referral(
                        r.s("id"), r.s("name"), r.s("district"), r.s("date"), r.s("status"),
                        r.optDouble("kw", 0.0), r.optLong("reward"), r.s("payout"), r.s("paidOn"),
                    )
                },
                refPaid = totals.optLong("paid"), refPending = totals.optLong("pending"),
                slabs = o.optJSONArray("slabs").map { s -> Slab(s.optDouble("from"), s.optDouble("to"), s.optLong("rate")) },
                referralTerms = o.s("referralTerms"),
                tickets = o.optJSONArray("tickets").map { t ->
                    Ticket(
                        t.s("id"), t.s("date"), t.s("category"), t.s("description"), t.s("errorCode"),
                        t.optInt("photos"), t.s("status"), t.s("assigned"), t.s("visit"), t.s("resolution"), t.optInt("rating"),
                    )
                },
                categories = if (cats == null) emptyList() else (0 until cats.length()).map { cats.optString(it) },
                announcement = o.s("announcement"),
                supportPhone = support.s("phone").ifBlank { "9288176289" },
                supportWhatsapp = support.s("whatsapp").ifBlank { "9288176289" },
            )
        }
    }
}

/** The last "home" answer, kept on the phone so the app opens instantly and works without signal. */
object Store {
    private const val PREFS = "data"

    fun save(c: Context, raw: JSONObject) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("home", raw.toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    fun load(c: Context): Home? {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("home", "") ?: ""
        if (s.isBlank()) return null
        return try { Home.parse(JSONObject(s)) } catch (_: Exception) { null }
    }

    fun savedAt(c: Context): Long = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("at", 0L)

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Fetches fresh data, saves it, and returns it with what changed since the last copy. */
    fun refresh(c: Context): Pair<Home, List<String>> {
        val old = load(c)
        val raw = Api.call(c, "home")
        val fresh = Home.parse(raw)
        save(c, raw)
        return fresh to changes(old, fresh)
    }

    /** Plain-language list of what moved forward: stages done, ticket and referral updates. */
    fun changes(old: Home?, new: Home): List<String> {
        if (old == null) return emptyList()
        val out = mutableListOf<String>()
        new.systems.forEach { sys ->
            val before = old.systems.firstOrNull { it.id == sys.id } ?: return@forEach
            sys.stages.forEach { st ->
                val was = before.stages.firstOrNull { it.key == st.key }
                if (st.done && was != null && !was.done) out += "✅ ${st.title} — पूरा हुआ"
            }
            if (sys.action.isNotBlank() && sys.action != before.action) out += "📌 आपका काम: ${sys.action}"
        }
        new.tickets.forEach { t ->
            val was = old.tickets.firstOrNull { it.id == t.id }
            if (was != null && was.status != t.status) out += "🛠 Ticket ${t.id}: ${Fmt.ticketStatus(t.status)}"
            else if (was != null && t.visit.isNotBlank() && was.visit != t.visit) out += "🛠 Ticket ${t.id}: visit ${t.visit}"
        }
        new.referrals.forEach { r ->
            val was = old.referrals.firstOrNull { it.id == r.id }
            if (was != null && was.status != r.status) out += "🎁 ${r.name}: ${r.status}"
            if (was != null && was.payout != "Paid" && r.payout == "Paid") out += "💰 ${Fmt.money(r.reward)} reward भेज दिया गया (${r.name})"
        }
        if (new.announcement.isNotBlank() && new.announcement != old.announcement) out += "📢 ${new.announcement}"
        return out
    }
}

object Fmt {
    private val inr: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

    fun money(v: Long): String = "₹" + inr.format(v)

    fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun kw(v: Double): String = num(v) + " kW"

    fun ticketStatus(s: String) = when (s) {
        "Open" -> "मिल गया (Open)"
        "Assigned" -> "Technician assign हो गया"
        "Visit Scheduled" -> "Visit तय हो गई"
        "Resolved" -> "ठीक कर दिया गया — confirm करें"
        "Closed" -> "बंद (Closed)"
        "Reopened" -> "फिर से खोला गया"
        else -> s
    }

    fun mobile(m: String): String = if (m.length == 10) m.substring(0, 5) + " " + m.substring(5) else m
}
