package com.avip.solarcustomer

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A readable error from the office server. [sessionExpired] = log in again. */
class ApiException(message: String, val sessionExpired: Boolean = false) : Exception(message)

/** Who is logged in on this phone. */
object Session {
    private const val PREFS = "session"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun name(c: Context): String = prefs(c).getString("name", "") ?: ""
    fun mobile(c: Context): String = prefs(c).getString("mobile", "") ?: ""
    fun mustSetPin(c: Context) = prefs(c).getBoolean("mustSetPin", false)
    fun loggedIn(c: Context) = token(c).isNotBlank() && !mustSetPin(c)

    fun save(c: Context, token: String, name: String, mobile: String, mustSetPin: Boolean) {
        prefs(c).edit().putString("token", token).putString("name", name).putString("mobile", mobile)
            .putBoolean("mustSetPin", mustSetPin).apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        Store.clear(c)
    }
}

/**
 * Calls the Apps Script web app behind the AVIP Solar customer sheet.
 * Its address comes from config.json in the app's GitHub repo, so it can be
 * changed without a new app version.
 */
object Api {
    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-cust-5Tr8"
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/avipbizsolutions/AVIPSolarCustomer/main/config.json"
    private const val PREFS = "config"

    fun endpoint(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", "") ?: ""

    /** Reads config.json; keeps the last good address when offline. Returns true when an address is known. */
    fun refreshConfig(c: Context): Boolean {
        try {
            val conn = URL(CONFIG_URL + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            try {
                if (conn.responseCode == 200) {
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ep = o.optString("endpoint").trim()
                    if (ep.startsWith("https://")) {
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("endpoint", ep).apply()
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        return endpoint(c).startsWith("https://")
    }

    /** Blocking call; run it off the main thread. Throws [ApiException] with a message for the customer. */
    fun call(c: Context, kind: String, body: JSONObject = JSONObject(), timeoutMs: Int = 60000): JSONObject {
        val ep = endpoint(c)
        if (!ep.startsWith("https://")) throw ApiException("AVIP server अभी तैयार नहीं है। थोड़ी देर बाद कोशिश करें।")
        body.put("key", SHARED_KEY).put("kind", kind)
        if (!body.has("token")) body.put("token", Session.token(c))
        body.put("appVersion", AppInfo.currentVersionName(c))
        val text = try {
            val conn = URL(ep).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException("Internet नहीं है। Connection check करके फिर कोशिश करें।")
        }
        val o = try { JSONObject(text) } catch (_: Exception) {
            throw ApiException("Server से सही जवाब नहीं मिला। एक मिनट बाद फिर कोशिश करें।")
        }
        if (!o.optBoolean("ok")) {
            val msg = o.optString("error").ifBlank { "कुछ गड़बड़ हो गई।" }
            if (msg.startsWith("SESSION:")) throw ApiException(msg.removePrefix("SESSION:").trim(), sessionExpired = true)
            if (msg == "unknown kind") throw ApiException("Server update हो रहा है। थोड़ी देर बाद कोशिश करें।")
            throw ApiException(msg)
        }
        return o
    }
}
