package ir.abdev.samadx

import android.content.Context
import ir.abdev.samadx.data.AccountInfo
import ir.abdev.samadx.data.Accounts
import ir.abdev.samadx.data.ApiClient
import ir.abdev.samadx.data.ApiException
import ir.abdev.samadx.data.Repository
import ir.abdev.samadx.data.Store

/**
 * ساده‌ترین شکل تزریق وابستگی. هر حساب یک «نشست» مستقل دارد (Store + ApiClient + Repository)
 * با فایل‌های ذخیره‌ی جدا؛ پس توکن، سلف، کش هفته‌ها، موجودی، گزارش و کد تحویل هر حساب جداست.
 * `store` / `api` / `repo` همیشه به نشستِ حساب فعال اشاره می‌کنند، پس بقیه‌ی برنامه
 * برای چندحسابی نیازی به تغییر ندارد: فقط حساب فعال را عوض کنید و صفحه را از نو بسازید.
 */
object Graph {
    private lateinit var appContext: Context

    lateinit var accounts: Accounts
        private set

    private class Session(val store: Store, val api: ApiClient, val repo: Repository)

    private val sessions = HashMap<String, Session>()
    private var detached: Session? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        accounts = Accounts(appContext)
        accounts.migrateLegacy()
    }

    private fun build(prefsName: String, secureName: String): Session {
        val st = Store(appContext, prefsName, secureName)
        val api = ApiClient(st)
        return Session(st, api, Repository(api, st))
    }

    private fun sessionOf(info: AccountInfo): Session =
        sessions.getOrPut(info.id) { build(info.prefsName, info.secureName) }

    private fun current(): Session {
        accounts.active()?.let { return sessionOf(it) }
        // حسابی نیست (مثلاً درست بعد از حذف آخرین حساب): نشست خالی تا هیچ‌چیز نخوابد
        return detached ?: build("samad_detached", "samad_detached_sec").also { detached = it }
    }

    val store: Store get() = current().store
    val api: ApiClient get() = current().api
    val repo: Repository get() = current().repo

    /** آیا حساب فعالی با اطلاعات ورود معتبر داریم؟ */
    fun hasSession(): Boolean = accounts.active()?.let { sessionOf(it).store.hasCredentials() } == true

    fun storeOf(info: AccountInfo): Store = sessionOf(info).store

    /** نام نمایشی حساب: نام و نام خانوادگی اگر داشتیم، وگرنه شماره دانشجویی. */
    fun labelOf(info: AccountInfo): String =
        storeOf(info).fullName?.takeIf { it.isNotBlank() } ?: info.id

    /**
     * ورود و ثبت حساب. اگر همین نام کاربری قبلاً ثبت شده، همان حساب (و کش‌هایش) به‌روز می‌شود،
     * وگرنه حساب تازه ساخته می‌شود (تا سقف Accounts.MAX). در پایان این حساب فعال است.
     */
    suspend fun login(username: String, password: String) {
        val id = Accounts.normalize(username)
        val existing = accounts.find(id)
        if (existing == null && !accounts.canAdd()) {
            throw ApiException("حداکثر ${Accounts.MAX} حساب می‌توانید اضافه کنید. ابتدا از یکی از حساب‌ها خارج شوید.")
        }
        val info = existing ?: accounts.newSlot(id)
        val session = sessionOf(info)
        try {
            session.repo.login(username, password)
        } catch (e: Throwable) {
            if (existing == null) { // ورود ناموفق نباید ردی از حساب نیمه‌کاره بگذارد
                session.store.clearAll()
                sessions.remove(id)
            }
            throw e
        }
        if (existing == null) accounts.add(info)
        accounts.setActive(id)
    }

    fun switchTo(id: String) {
        accounts.setActive(id)
    }

    /**
     * خروج از یک حساب: توکن، رمز و همه‌ی کش‌های همان حساب پاک می‌شود؛ بقیه‌ی حساب‌ها دست‌نخورده می‌مانند.
     * @return true اگر حسابی باقی مانده باشد (که اولی‌اش فعال شده است)
     */
    fun removeAccount(id: String): Boolean {
        accounts.find(id)?.let { info ->
            val s = sessionOf(info)
            s.api.reset()
            s.store.clearAll()
            sessions.remove(id)
        }
        accounts.remove(id)
        return accounts.count() > 0
    }
}
