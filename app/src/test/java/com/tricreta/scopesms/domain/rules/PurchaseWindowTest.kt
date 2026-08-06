package com.tricreta.scopesms.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bundle purchase-window feature: which minutes of the day a payment must
 * arrive in for a matched bundle to still count as an instant purchase.
 */
class PurchaseWindowTest {

    // --- contains: a normal same-day window ----------------------------------

    @Test
    fun `contains is inclusive of both boundaries on a same-day window`() {
        val window = PurchaseWindow(startMinute = 16 * 60, endMinute = 22 * 60 + 59) // 4:00 PM to 10:59 PM

        assertThat(window.contains(16 * 60)).isTrue() // exactly 4:00 PM
        assertThat(window.contains(22 * 60 + 59)).isTrue() // exactly 10:59 PM
        assertThat(window.contains(19 * 60)).isTrue() // well inside, 7:00 PM
    }

    @Test
    fun `contains is false just outside a same-day window on both sides`() {
        val window = PurchaseWindow(startMinute = 16 * 60, endMinute = 22 * 60 + 59)

        assertThat(window.contains(16 * 60 - 1)).isFalse() // one minute early
        assertThat(window.contains(22 * 60 + 60)).isFalse() // one minute late (23:00)
    }

    // --- contains: a window that wraps past midnight --------------------------

    @Test
    fun `contains handles a window that wraps past midnight`() {
        val window = PurchaseWindow(startMinute = 22 * 60, endMinute = 2 * 60) // 22:00 to 02:00

        assertThat(window.contains(23 * 60)).isTrue() // 23:00, after start
        assertThat(window.contains(1 * 60)).isTrue() // 01:00, before end
        assertThat(window.contains(22 * 60)).isTrue() // exactly the start
        assertThat(window.contains(2 * 60)).isTrue() // exactly the end
        assertThat(window.contains(12 * 60)).isFalse() // noon — well outside either side
    }

    // --- isAllDay ---------------------------------------------------------------

    @Test
    fun `isAllDay is true only for DEFAULT and its equivalent`() {
        assertThat(PurchaseWindow.DEFAULT.isAllDay).isTrue()
        assertThat(PurchaseWindow(0, 1439).isAllDay).isTrue()

        assertThat(PurchaseWindow(0, 1438).isAllDay).isFalse()
        assertThat(PurchaseWindow(1, 1439).isAllDay).isFalse()
        assertThat(PurchaseWindow(16 * 60, 22 * 60 + 59).isAllDay).isFalse()
    }

    @Test
    fun `an all-day window contains every minute`() {
        assertThat(PurchaseWindow.DEFAULT.contains(0)).isTrue()
        assertThat(PurchaseWindow.DEFAULT.contains(719)).isTrue()
        assertThat(PurchaseWindow.DEFAULT.contains(1439)).isTrue()
    }

    // --- describe() -------------------------------------------------------------

    @Test
    fun `describe renders a normal window in 12-hour clock form`() {
        val window = PurchaseWindow(startMinute = 16 * 60, endMinute = 22 * 60 + 59)

        assertThat(window.describe()).isEqualTo("4:00 PM to 10:59 PM")
    }

    @Test
    fun `describe renders a wrapping window`() {
        val window = PurchaseWindow(startMinute = 22 * 60, endMinute = 2 * 60)

        assertThat(window.describe()).isEqualTo("10:00 PM to 2:00 AM")
    }

    @Test
    fun `describe renders midnight and noon correctly, not 0 o'clock`() {
        val midnightToNoon = PurchaseWindow(startMinute = 0, endMinute = 12 * 60)

        assertThat(midnightToNoon.describe()).isEqualTo("12:00 AM to 12:00 PM")
    }

    // --- minuteOfDayFrom: parsing M-Pesa's reported time -----------------------

    @Test
    fun `minuteOfDayFrom parses a space-separated time`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("1:06 PM")).isEqualTo(13 * 60 + 6)
    }

    @Test
    fun `minuteOfDayFrom parses a time with no space before AM-PM`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("1:06PM")).isEqualTo(13 * 60 + 6)
    }

    @Test
    fun `minuteOfDayFrom parses a time with periods in AM-PM`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("1:06 P.M.")).isEqualTo(13 * 60 + 6)
    }

    @Test
    fun `minuteOfDayFrom handles the midnight and noon edge cases`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("12:00 AM")).isEqualTo(0)
        assertThat(PurchaseWindow.minuteOfDayFrom("12:00 PM")).isEqualTo(12 * 60)
    }

    @Test
    fun `minuteOfDayFrom is case-insensitive`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("12:30 am")).isEqualTo(30)
    }

    @Test
    fun `minuteOfDayFrom returns null for an unparseable string`() {
        assertThat(PurchaseWindow.minuteOfDayFrom("not a time")).isNull()
        assertThat(PurchaseWindow.minuteOfDayFrom("")).isNull()
        assertThat(PurchaseWindow.minuteOfDayFrom("25:00 PM")).isNull()
    }

    @Test
    fun `minuteOfDayFrom extracts the time from a full raw string, not just a bare time`() {
        // The real caller passes MpesaPayment.time, which is already just the
        // time fragment, but the parser should still find it inside noise.
        assertThat(PurchaseWindow.minuteOfDayFrom("at 1:06 PM")).isEqualTo(13 * 60 + 6)
    }
}
