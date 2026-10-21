package ir.abdev.samadx.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** قالب‌بندی عددها و متن فارسی. */
object Fa {

    // سرور مبلغ‌ها را به ریال می‌دهد؛ در برنامه به تومان (یک صفر کمتر) نمایش داده می‌شود.
    const val CURRENCY = "تومان"
    private const val RIAL_PER_TOMAN = 10L

    private val persian = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') persian[c - '0'] else c)
        return sb.toString()
    }

    /** ارقام فارسی/عربی را به لاتین برمی‌گرداند (برای نام کاربری). */
    fun toLatinDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** عدد با جداکننده‌ی هزارگان فارسی؛ علامت منفی نمی‌گذارد (قدر مطلق). */
    fun num(n: Long): String {
        val s = abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('٬')
            sb.append(c)
        }
        return digits(sb.toString())
    }

    /** @param rial مبلغ به ریال، همان‌طور که از سرور آمده؛ به تومان نمایش داده می‌شود. */
    fun money(rial: Long): String = "${num(rial / RIAL_PER_TOMAN)} $CURRENCY"

    fun score(v: Double): String = digits(String.format(Locale.US, "%.1f", v)).replace('.', '٫')

    fun count(n: Int): String = digits(n.toString())

    /**
     * تاریخ نسبی نسبت به امروز: «پریروز، دیروز، امروز، فردا، پس‌فردا» و بعد از آن
     * «۳ روز دیگر» / «۴ روز پیش» (کلمه‌ها قابل تغییرند، مثلاً «بعد» و «قبل»).
     * @param diffDays تاریخ منهای امروز به روز
     */
    fun relativeDay(diffDays: Long, forward: String = "دیگر", back: String = "پیش"): String = when (diffDays) {
        0L -> "امروز"
        1L -> "فردا"
        2L -> "پس‌فردا"
        -1L -> "دیروز"
        -2L -> "پریروز"
        else -> if (diffDays > 0) "${count(diffDays.toInt())} روز $forward" else "${count((-diffDays).toInt())} روز $back"
    }

    private val inputFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** «2026-10-04 12:46:09» → «۱۲ مهر ۱۴۰۵ ـ ۱۲:۴۶» */
    fun dateTime(raw: String): String = try {
        val t = LocalDateTime.parse(raw, inputFmt)
        "${Jalali.format(t.toLocalDate())} ـ ${digits("%02d:%02d".format(Locale.US, t.hour, t.minute))}"
    } catch (e: Exception) {
        raw
    }

    /** «۳ روز پیش»، «۵ ساعت پیش» یا «چند دقیقه پیش». */
    fun ago(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val min = ((nowMs - thenMs) / 60_000).coerceAtLeast(0)
        return when {
            min < 60 -> "چند دقیقه پیش"
            min < 24 * 60 -> "${count((min / 60).toInt())} ساعت پیش"
            else -> "${count((min / (24 * 60)).toInt())} روز پیش"
        }
    }

    /** «۳ روز پیش (۱۴۰۵/۰۷/۲۱ ـ ۱۳:۴۵)». */
    fun lastUpdate(thenMs: Long): String {
        val t = java.time.Instant.ofEpochMilli(thenMs).atZone(java.time.ZoneId.of("Asia/Tehran")).toLocalDateTime()
        val raw = "%04d-%02d-%02d %02d:%02d:%02d".format(Locale.US, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
        return ago(thenMs) + " (" + dateTime(raw) + ")"
    }
}
