package ir.abdev.samadx.util

import java.time.DayOfWeek
import java.time.LocalDate

/** تبدیل تاریخ میلادی به شمسی و قالب‌بندی‌های فارسی. */
object Jalali {

    private val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    private val months = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** @return سه‌تایی (سال، ماه، روز) شمسی */
    fun fromGregorian(y: Int, m: Int, d: Int): Triple<Int, Int, Int> {
        val gy2 = if (m > 2) y + 1 else y
        var days = 355666 + (365 * y) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + d + gdm[m - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    fun of(date: LocalDate) = fromGregorian(date.year, date.monthValue, date.dayOfMonth)

    /** مثلاً «۲۱ مهر ۱۴۰۵» */
    fun format(date: LocalDate): String {
        val (y, m, d) = of(date)
        return Fa.digits("$d ${months[m - 1]} $y")
    }

    /** مثلاً «۲۱ مهر» */
    fun formatNoYear(date: LocalDate): String {
        val (_, m, d) = of(date)
        return Fa.digits("$d ${months[m - 1]}")
    }

    /** مثلاً «۱۴۰۵/۰۷/۲۱» */
    fun formatNumeric(date: LocalDate): String {
        val (y, m, d) = of(date)
        return Fa.digits("%04d/%02d/%02d".format(java.util.Locale.US, y, m, d))
    }

    fun weekdayName(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> "شنبه"
        DayOfWeek.SUNDAY -> "یکشنبه"
        DayOfWeek.MONDAY -> "دوشنبه"
        DayOfWeek.TUESDAY -> "سه‌شنبه"
        DayOfWeek.WEDNESDAY -> "چهارشنبه"
        DayOfWeek.THURSDAY -> "پنجشنبه"
        DayOfWeek.FRIDAY -> "جمعه"
    }

    /** شروع هفته (شنبه) برای هر روز. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value + 1) % 7).toLong())

    /** مثلاً «۱۸ مهر تا ۲۴ مهر ۱۴۰۵» */
    fun weekTitle(start: LocalDate): String {
        val end = start.plusDays(6)
        val (sy, sm, _) = of(start)
        val (ey, em, _) = of(end)
        return if (sy == ey && sm == em) {
            "${formatNoYear(start).substringBefore(' ')} تا ${format(end)}"
        } else {
            "${formatNoYear(start)} تا ${format(end)}"
        }
    }
}
