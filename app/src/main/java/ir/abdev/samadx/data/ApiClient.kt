package ir.abdev.samadx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** خطای قابل‌نمایش به کاربر. http = 0 یعنی خطای شبکه. */
class ApiException(val userMessage: String, val code: Int = 0, val http: Int = 0) : Exception(userMessage)

/** نشست معتبر نیست و کاربر باید دوباره وارد شود. */
class AuthRequiredException(message: String = "برای ادامه باید دوباره وارد شوید.") : Exception(message)

/** پاسخ استاندارد سرور: {type, code, message, messageFa, payload}. payload می‌تواند JSONObject / JSONArray / String باشد. */
class Envelope(val type: String, val code: Int, val message: String?, val payload: Any?) {
    fun obj(): JSONObject = payload as? JSONObject ?: throw ApiException("ساختار پاسخ سرور غیرمنتظره بود.")
    fun arr() = payload as? org.json.JSONArray ?: throw ApiException("ساختار پاسخ سرور غیرمنتظره بود.")
}

private class Raw(val http: Int, val body: String)

/**
 * کلاینت HTTP ساده روی HttpURLConnection.
 * - توکن را خودکار می‌گیرد، قبل از انقضا تمدید می‌کند و در صورت 401 با refresh_token یا
 *   با اطلاعات ذخیره‌شده دوباره وارد می‌شود و درخواست را یک‌بار تکرار می‌کند.
 */
class ApiClient(private val store: Store) {

    private val lock = Mutex()
    private var token: String? = null
    private var expiresAt: Long = 0L
    private var loaded = false

    /** بعد از خروج از حساب: توکن حافظه‌ی موقت را فراموش می‌کند. */
    fun reset() {
        token = null
        expiresAt = 0L
        loaded = false
    }

    // ---------------------------------------------------------------- ورود

    /** ورود با نام کاربری/رمز. در صورت موفقیت اطلاعات ورود هم برای ورود خودکار ذخیره می‌شود. */
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            requestToken(passwordForm(username, password), null)
            store.saveCredentials(username, password)
        }
    }

    private fun passwordForm(u: String, p: String) =
        "username=${enc(u)}&password=${enc(p)}&grant_type=password&scope=read+write"

    // ---------------------------------------------------------------- فراخوانی API

    suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): Envelope = withContext(Dispatchers.IO) {
        val first = validToken()
        var r = send(method, path, query, body, first)
        if (r.http == 401) {
            val fresh = lock.withLock {
                val current = token
                if (current != null && current != first) current else {
                    token = null
                    expiresAt = 0L
                    authenticate()
                }
            }
            r = send(method, path, query, body, fresh)
            if (r.http == 401) throw AuthRequiredException()
        }
        parseEnvelope(r)
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()) = call("GET", path, query)

    // ---------------------------------------------------------------- توکن

    private suspend fun validToken(): String = lock.withLock {
        if (!loaded) {
            token = store.accessToken
            expiresAt = store.expiresAt
            loaded = true
        }
        val t = token
        if (t != null && System.currentTimeMillis() < expiresAt) t else authenticate()
    }

    /** باید داخل lock صدا زده شود. ابتدا refresh_token، بعد اطلاعات ذخیره‌شده. */
    private fun authenticate(): String {
        val rt = store.refreshToken
        if (rt != null) {
            try {
                return requestToken("grant_type=refresh_token&refresh_token=${enc(rt)}", rt)
            } catch (e: ApiException) {
                if (e.http == 0) throw e // مشکل شبکه؛ بی‌دلیل رمز را امتحان نکن
            }
        }
        val u = store.username()
        val p = store.password()
        if (u == null || p == null) throw AuthRequiredException()
        try {
            return requestToken(passwordForm(u, p), null)
        } catch (e: ApiException) {
            if (e.http in 400..401) {
                throw AuthRequiredException("نام کاربری یا رمز عبور معتبر نیست. دوباره وارد شوید.")
            }
            throw e
        }
    }

    private fun requestToken(form: String, oldRefresh: String?): String {
        val r = try {
            raw("POST", "/oauth/token", emptyMap(), form, "application/x-www-form-urlencoded; charset=UTF-8", null, BASIC)
        } catch (e: IOException) {
            throw networkError()
        }
        if (r.http != 200) {
            val msg = if (r.http == 400 || r.http == 401) "نام کاربری یا رمز عبور اشتباه است." else "خطای سرور هنگام ورود (${r.http})"
            throw ApiException(msg, http = r.http)
        }
        val j = try {
            JSONObject(r.body)
        } catch (e: JSONException) {
            throw ApiException("پاسخ نامعتبر از سرور هنگام ورود.", http = r.http)
        }
        val access = j.optString("access_token")
        if (access.isEmpty()) throw ApiException("توکن دریافت نشد.", http = r.http)
        val refresh = if (j.has("refresh_token") && !j.isNull("refresh_token")) j.getString("refresh_token") else oldRefresh
        val ttlSec = maxOf(0L, j.optLong("expires_in", 3600L) - 60L)
        val exp = System.currentTimeMillis() + ttlSec * 1000L

        val name = listOf(j.optString("first_name"), j.optString("last_name")).filter { it.isNotBlank() && it != "null" }.joinToString(" ")
        if (name.isNotBlank()) store.fullName = name

        store.saveTokens(access, refresh, exp)
        token = access
        expiresAt = exp
        loaded = true
        return access
    }

    // ---------------------------------------------------------------- پایین‌ترین لایه

    private fun send(method: String, path: String, query: Map<String, String>, body: JSONObject?, bearer: String): Raw =
        try {
            raw(method, path, query, body?.toString(), "application/json", bearer, null)
        } catch (e: IOException) {
            throw networkError()
        }

    private fun raw(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        contentType: String?,
        bearer: String?,
        basic: String?
    ): Raw {
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val c = URL(BASE + path + qs).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Cache-Control", "no-cache")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (basic != null) c.setRequestProperty("Authorization", basic)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Raw(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun parseEnvelope(r: Raw): Envelope {
        if (r.body.isBlank()) {
            if (r.http in 200..299) return Envelope("SUCCESS", 0, null, null)
            throw ApiException("خطای سرور (${r.http})", http = r.http)
        }
        val j = try {
            JSONObject(r.body)
        } catch (e: JSONException) {
            throw ApiException("پاسخ نامعتبر از سرور (${r.http})", http = r.http)
        }
        val type = j.optString("type")
        val code = j.optInt("code")
        val msg = j.str("messageFa") ?: j.str("message")
        if (r.http !in 200..299 || type == "ERROR") {
            throw ApiException(msg ?: "خطای ناشناخته (${r.http})", code, r.http)
        }
        val payload = if (j.isNull("payload")) null else j.get("payload")
        return Envelope(type, code, msg, payload)
    }

    private fun networkError() = ApiException(
        "ارتباط با سرور برقرار نشد. اینترنت را بررسی کنید " +
            "(اگر VPN یا IP خارج از ایران دارید، خاموشش کنید).",
        http = 0
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val BASE = "https://saba.tvu.ac.ir"
        // کلاینت رسمی اپ موبایل سماد: samad-mobile / samad-mobile-secret
        const val BASIC = "Basic c2FtYWQtbW9iaWxlOnNhbWFkLW1vYmlsZS1zZWNyZXQ="
    }
}
