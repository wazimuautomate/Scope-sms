package com.tricreta.scopesms.domain.rules

/**
 * The time-of-day window a bundle can be purchased in — Safaricom restricts
 * some offers (e.g. "1GB 1Hr" @ Ksh19) to specific hours. [startMinute]/
 * [endMinute] are minutes since local midnight (0..1439). Supports wrapping
 * past midnight (e.g. 22:00 to 02:00) via [contains].
 */
data class PurchaseWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..MAX_MINUTE) { "startMinute out of range: $startMinute" }
        require(endMinute in 0..MAX_MINUTE) { "endMinute out of range: $endMinute" }
    }

    val isAllDay: Boolean get() = this == DEFAULT

    /** True if [minuteOfDay] (0..1439) falls inside this window. */
    fun contains(minuteOfDay: Int): Boolean =
        if (isAllDay) true
        else if (startMinute <= endMinute) minuteOfDay in startMinute..endMinute
        else minuteOfDay >= startMinute || minuteOfDay <= endMinute // wraps past midnight

    /** "4:00 PM to 10:59 PM" — always non-blank, for {purchase_window}. */
    fun describe(): String = "${formatMinute(startMinute)} to ${formatMinute(endMinute)}"

    companion object {
        private const val MAX_MINUTE = 1439

        /** Unrestricted — the bundle's window before the agent sets one. */
        val DEFAULT = PurchaseWindow(0, MAX_MINUTE)

        private fun formatMinute(minute: Int): String {
            val hour24 = minute / 60
            val min = minute % 60
            val isPm = hour24 >= 12
            val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
            return "%d:%02d %s".format(hour12, min, if (isPm) "PM" else "AM")
        }

        /**
         * Parses M-Pesa's reported time (e.g. "1:06 PM", "1:06PM", "1:06 P.M.")
         * into minutes since local midnight. Null if unparseable — callers
         * should treat null as "no window restriction applies" (fail open),
         * since every payment before this feature existed got instant
         * confirmation and this parser reads the SAME time format
         * `MpesaParser` already validated to accept the message at all, so an
         * unparseable time here should be near-impossible in practice.
         */
        fun minuteOfDayFrom(raw: String): Int? {
            val match = TIME_PATTERN.find(raw) ?: return null
            val hour12 = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour12 !in 1..12 || minute !in 0..59) return null
            val isPm = match.groupValues[3].equals("p", ignoreCase = true)
            val hour24 = when {
                hour12 == 12 && !isPm -> 0
                hour12 == 12 && isPm -> 12
                isPm -> hour12 + 12
                else -> hour12
            }
            return hour24 * 60 + minute
        }

        private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})\s*([AaPp])\.?[Mm]\.?""")
    }
}
