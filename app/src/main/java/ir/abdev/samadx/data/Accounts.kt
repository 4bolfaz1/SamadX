package ir.abdev.samadx.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * یک حساب ذخیره‌شده. id = نام کاربری (شماره دانشجویی، لاتین و کوچک).
 * prefsName / secureName نام دو فایل ذخیره‌ی همین حساب‌اند (Store) تا داده‌ی حساب‌ها قاطی نشود.
 */
class AccountInfo(val id: String, val prefsName: String, val secureName: String)

/**
 * فهرست حساب‌ها (تا MAX حساب) و حساب فعال. خودِ فهرست هم رمزشده نگه داشته می‌شود
 * چون شامل نام کاربری‌هاست. همچنین نسخه‌ی پذیرفته‌شده‌ی متن حریم خصوصی را نگه می‌دارد.
 */
class Accounts(private val context: Context) {

    private val secure = SecureStore(context, "samad_secure_accounts")
    private val plain = context.getSharedPreferences("samad_global", Context.MODE_PRIVATE)

    private val list: MutableList<AccountInfo> = readList()
    private var activeId: String? = secure.get("active")

    private fun readList(): MutableList<AccountInfo> {
        val out = ArrayList<AccountInfo>()
        val raw = secure.get("list") ?: return out
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += AccountInfo(o.getString("id"), o.getString("p"), o.getString("s"))
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    private fun save() {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("id", x.id).put("p", x.prefsName).put("s", x.secureName))
        secure.put("list", a.toString())
        secure.put("active", activeId)
    }

    fun all(): List<AccountInfo> = list.toList()
    fun count(): Int = list.size
    fun canAdd(): Boolean = list.size < MAX
    fun find(id: String): AccountInfo? = list.firstOrNull { it.id == id }

    /** حساب فعال؛ اگر ذخیره‌شده‌ی فعال نبود اولین حساب. */
    fun active(): AccountInfo? = activeId?.let { find(it) } ?: list.firstOrNull()

    fun setActive(id: String) {
        if (find(id) == null) return
        activeId = id
        save()
    }

    /** فایل‌های ذخیره‌ی یک حساب تازه (هنوز در فهرست نیست؛ با add ثبت می‌شود). */
    fun newSlot(id: String): AccountInfo {
        val n = plain.getInt("slot_seq", 0) + 1
        plain.edit().putInt("slot_seq", n).apply()
        return AccountInfo(id, "samad_acc_$n", "samad_sec_$n")
    }

    fun add(info: AccountInfo) {
        if (find(info.id) != null) return
        list += info
        save()
    }

    /** حساب را از فهرست برمی‌دارد؛ اگر فعال بود، اولین حساب باقی‌مانده فعال می‌شود. */
    fun remove(id: String) {
        list.removeAll { it.id == id }
        if (activeId == id) activeId = list.firstOrNull()?.id
        save()
    }

    // ---- حریم خصوصی ----
    val privacyAccepted: Boolean get() = plain.getInt("privacy_version", 0) >= PRIVACY_VERSION

    fun acceptPrivacy() {
        plain.edit().putInt("privacy_version", PRIVACY_VERSION).apply()
    }

    /**
     * کاربر نسخه‌ی قبلی (تک‌حسابی): فایل‌های قدیمی (samad_prefs / samad_secure) همان حساب اول می‌شوند؛
     * چیزی کپی یا پاک نمی‌شود.
     */
    fun migrateLegacy() {
        if (list.isNotEmpty()) return
        val legacy = SecureStore(context, "samad_secure")
        val u = legacy.get("u") ?: return
        val id = normalize(u)
        list += AccountInfo(id, "samad_prefs", "samad_secure")
        activeId = id
        save()
    }

    companion object {
        /** حداکثر تعداد حساب؛ برای افزایش در آینده فقط همین عدد را عوض کنید. */
        const val MAX = 3

        /** با تغییر محتوای مهم متن حریم خصوصی این عدد را بالا ببرید تا همه دوباره تأیید کنند. */
        const val PRIVACY_VERSION = 1

        fun normalize(username: String): String =
            ir.abdev.samadx.util.Fa.toLatinDigits(username).trim().lowercase()
    }
}
