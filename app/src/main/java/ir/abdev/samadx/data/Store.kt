package ir.abdev.samadx.data

import android.content.Context

/**
 * نگه‌داری وضعیت ورود و کش‌های «یک حساب»: اطلاعات ورود و توکن‌ها (رمزشده)، سلف و کش‌ها.
 * هر حساب دو فایل مستقل دارد (prefsName و secureName) تا داده‌ی حساب‌ها هیچ‌وقت قاطی نشود.
 */
class Store(context: Context, prefsName: String = "samad_prefs", secureName: String = "samad_secure") {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val secure = SecureStore(context, secureName)

    // ---- اطلاعات ورود (برای ورود خودکار) ----
    fun saveCredentials(username: String, password: String) {
        secure.put("u", username)
        secure.put("p", password)
    }

    /** رمز نامعتبر شده (مثلاً عوض شده)؛ نام کاربری و کش‌ها می‌مانند تا کاربر دوباره وارد شود. */
    fun clearPassword() {
        secure.put("p", null)
    }

    fun username(): String? = secure.get("u")
    fun password(): String? = secure.get("p")
    fun hasCredentials(): Boolean = username() != null && password() != null

    // ---- توکن‌ها ----
    val accessToken: String? get() = secure.get("at")
    val refreshToken: String? get() = secure.get("rt")
    val expiresAt: Long get() = prefs.getLong("exp", 0L)

    fun saveTokens(access: String, refresh: String?, expiresAtMs: Long) {
        secure.put("at", access)
        secure.put("rt", refresh)
        prefs.edit().putLong("exp", expiresAtMs).apply()
    }

    fun clearTokens() {
        secure.put("at", null)
        secure.put("rt", null)
        prefs.edit().remove("exp").apply()
    }

    // ---- سلف انتخاب‌شده ----
    var selfId: Long
        get() = prefs.getLong("self_id", -1L)
        set(v) = prefs.edit().putLong("self_id", v).apply()

    var selfName: String?
        get() = prefs.getString("self_name", null)
        set(v) = prefs.edit().putString("self_name", v).apply()

    // ---- کش ماندگار تا زمان خروج از حساب ----
    // سلف‌ها تقریباً ثابت‌اند؛ فقط یک بار از سرور گرفته می‌شوند (JSON خام).
    /** نام و نام خانوادگی؛ از پاسخ ورود (oauth/token) گرفته می‌شود و درخواست جداگانه‌ای لازم نیست. */
    var fullName: String?
        get() = prefs.getString("full_name", null)
        set(v) = prefs.edit().putString("full_name", v).apply()

    var selfsJson: String?
        get() = prefs.getString("selfs_json", null)
        set(v) = prefs.edit().putString("selfs_json", v).apply()

    // ---- کش هفته‌ها: JSON خام + زمان دریافت + زمان انقضا (تعیین‌شده توسط Repository) ----
    class Stored(val fetchedAt: Long, val json: String)

    fun putWeekJson(key: String, json: String, expiresAt: Long) {
        val now = System.currentTimeMillis()
        val e = prefs.edit()
        for (k in prefs.all.keys) {
            if (!k.startsWith("wk:")) continue
            val x = try { org.json.JSONObject(prefs.getString(k, "{}") ?: "{}").optLong("x") } catch (ex: Exception) { 0L }
            if (x < now) e.remove(k)
        }
        e.putString(
            "wk:$key",
            org.json.JSONObject().put("t", now).put("x", expiresAt).put("j", json).toString()
        ).apply()
    }

    /** null اگر نبود یا منقضی شده بود. */
    fun getWeekJson(key: String): Stored? {
        val o = try { org.json.JSONObject(prefs.getString("wk:$key", null) ?: return null) } catch (e: Exception) { return null }
        if (o.optLong("x") < System.currentTimeMillis()) return null
        return Stored(o.optLong("t"), o.optString("j"))
    }

    // ---- اعتبار: payload خام سرور + زمان آخرین دریافت از سرور (تغییرات آفلاین روی فیلد credit اعمال می‌شود) ----
    fun putCreditJson(json: String, syncedAt: Long) {
        prefs.edit().putString("credit_cache", org.json.JSONObject().put("t", syncedAt).put("j", json).toString()).apply()
    }

    fun getCreditJson(): Stored? {
        val o = try { org.json.JSONObject(prefs.getString("credit_cache", null) ?: return null) } catch (e: Exception) { return null }
        return Stored(o.optLong("t"), o.optString("j"))
    }

    // ---- گزارش عملکرد: همیشه کش؛ با هر اقدام (رزرو/لغو/انتقال/افزایش اعتبار) dirty می‌شود ----
    fun putLogJson(json: String) {
        prefs.edit().putString("log_cache", org.json.JSONObject().put("t", System.currentTimeMillis()).put("j", json).toString())
            .putBoolean("log_dirty", false).apply()
    }

    fun getLogJson(): Stored? {
        val o = try { org.json.JSONObject(prefs.getString("log_cache", null) ?: return null) } catch (e: Exception) { return null }
        return Stored(o.optLong("t"), o.optString("j"))
    }

    var logDirty: Boolean
        get() = prefs.getBoolean("log_dirty", false)
        set(v) = prefs.edit().putBoolean("log_dirty", v).apply()

    // ---- مبلغ اعتبار قبل از رفتن به صفحه‌ی پرداخت (برای تشخیص مقدار افزایش) ----
    var paymentBase: Long?
        get() = if (prefs.contains("pay_base")) prefs.getLong("pay_base", 0L) else null
        set(v) = prefs.edit().apply { if (v == null) remove("pay_base") else putLong("pay_base", v) }.apply()

    // ---- کش کد تحویل: رمزشده؛ هر کد تا پایان روز استفاده‌اش (یا تحویل غذا / لغو رزرو) نگه داشته می‌شود ----
    fun getCodeJson(reserveId: Long): String? {
        val e = readCodes().optJSONObject(reserveId.toString()) ?: return null
        if (e.optLong("x") < System.currentTimeMillis()) return null
        return e.optString("j").ifEmpty { null }
    }

    fun putCodeJson(reserveId: Long, json: String, expiresAt: Long) {
        val all = readCodes()
        all.put(reserveId.toString(), org.json.JSONObject().put("x", expiresAt).put("j", json))
        writeCodes(all)
    }

    fun removeCode(reserveId: Long) {
        val all = readCodes()
        if (all.has(reserveId.toString())) {
            all.remove(reserveId.toString())
            writeCodes(all)
        }
    }

    private fun writeCodes(all: org.json.JSONObject) {
        val now = System.currentTimeMillis()
        val pruned = org.json.JSONObject()
        for (k in all.keys()) {
            val e = all.getJSONObject(k)
            if (e.optLong("x") >= now) pruned.put(k, e)
        }
        secure.put("codes", pruned.toString())
    }

    private fun readCodes(): org.json.JSONObject =
        try { org.json.JSONObject(secure.get("codes") ?: "{}") } catch (e: Exception) { org.json.JSONObject() }

    /** خروج کامل: همه‌چیز پاک می‌شود. */
    fun clearAll() {
        secure.clear()
        prefs.edit().clear().apply()
    }

}
