package com.tricreta.scopesms.data.log

import androidx.room.Room
import com.tricreta.scopesms.data.AppDatabase
import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
import com.tricreta.scopesms.domain.money.KshAmount
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 8, exercised against a real (in-memory) SQLite database.
 *
 * Room's SQL is a string until something runs it: a typo in a column name or a
 * wrong boolean-sum idiom compiles perfectly and returns confidently wrong
 * numbers. The dashboard tiles are the agent's evidence that the app is working,
 * so these tests execute the queries rather than mocking the DAO — a mocked DAO
 * would only prove that the mapping code calls it.
 *
 * Robolectric, and therefore JDK 21 in CI — see build.yml.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityLogRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ActivityLogRepository

    /** 2026-07-16 10:00 in Nairobi (UTC+3). */
    private val nairobi: ZoneId = ZoneId.of("Africa/Nairobi")
    private val now: Instant = Instant.parse("2026-07-16T07:00:00Z")
    private val clock: Clock = Clock.fixed(now, nairobi)

    @Before
    fun setUp() {
        // RuntimeEnvironment.getApplication() rather than
        // androidx.test.core's ApplicationProvider: Robolectric already supplies
        // this, and it saves adding an androidx.test:core pin that the version
        // catalog doesn't carry and no build has ever resolved.
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).build()
        repository = ActivityLogRepository(db.activityLogDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun record(
        code: String,
        matchType: MatchType,
        notifyStatus: NotifyStatus,
        amountShillings: Long = 50,
        name: String? = "JOHN KAMAU",
        phone: String = "0722000000",
        at: Instant = now,
    ) = repository.record(
        transactionCode = code,
        senderName = name,
        senderPhone = phone,
        amount = KshAmount.ofShillings(amountShillings),
        matchType = matchType,
        notifyStatus = notifyStatus,
        timestamp = at.toEpochMilli(),
    )

    // --- Round trip ---------------------------------------------------------

    @Test
    fun `a recorded payment reads back with every field intact`() = runTest {
        repository.record(
            transactionCode = "TFA1B2C3D4",
            senderName = "JOHN KAMAU",
            senderPhone = "0722000000",
            amount = KshAmount(2050), // Ksh 20.50 — the fractional case KshAmount exists for.
            matchType = MatchType.UNMATCHED,
            notifyStatus = NotifyStatus.QUEUED,
            replyBody = "Hi JOHN, we received Ksh 20.50...",
        )

        val entry = repository.recent.first().single()

        assertEquals("TFA1B2C3D4", entry.transactionCode)
        assertEquals("JOHN KAMAU", entry.senderName)
        assertEquals(KshAmount(2050), entry.amount)
        assertEquals("20.50", entry.amount.format())
        assertEquals(MatchType.UNMATCHED, entry.matchType)
        assertEquals(NotifyStatus.QUEUED, entry.notifyStatus)
    }

    @Test
    fun `a missing sender name survives as null rather than a placeholder`() = runTest {
        record("TX1", MatchType.UNMATCHED, NotifyStatus.QUEUED, name = null)

        assertNull(repository.recent.first().single().senderName)
    }

    // --- The duplicate guard ------------------------------------------------

    @Test
    fun `the same transaction logged twice produces one row`() = runTest {
        // Some OEMs redeliver SMS_RECEIVED. One payment, one row.
        val first = record("TFA1B2C3D4", MatchType.MATCHED, NotifyStatus.SENT)
        val second = record("TFA1B2C3D4", MatchType.MATCHED, NotifyStatus.SENT)

        assertTrue("the first write should be recorded", first)
        assertFalse("the redelivery should report already-logged", second)
        assertEquals(1, repository.recent.first().size)
    }

    @Test
    fun `a redelivery cannot overwrite the first decision`() = runTest {
        record("TX1", MatchType.MATCHED, NotifyStatus.SENT)
        // A second decision that disagrees — first write must win.
        record("TX1", MatchType.UNMATCHED, NotifyStatus.FAILED)

        val entry = repository.recent.first().single()
        assertEquals(MatchType.MATCHED, entry.matchType)
        assertEquals(NotifyStatus.SENT, entry.notifyStatus)
    }

    // --- The send lifecycle -------------------------------------------------

    @Test
    fun `markSent moves a queued reply to sent and attaches the gateway id`() = runTest {
        record("TX1", MatchType.UNMATCHED, NotifyStatus.QUEUED)

        repository.markSent("TX1", gatewayMessageId = "msg-123")

        val entry = repository.recent.first().single()
        assertEquals(NotifyStatus.SENT, entry.notifyStatus)
        assertEquals("msg-123", entry.gatewayMessageId)
        assertNull(entry.failureReason)
    }

    @Test
    fun `markFailed records the agent-readable reason`() = runTest {
        record("TX1", MatchType.UNMATCHED, NotifyStatus.QUEUED)

        repository.markFailed("TX1", reason = "No internet connection — queued until connectivity returns")

        val entry = repository.recent.first().single()
        assertEquals(NotifyStatus.FAILED, entry.notifyStatus)
        assertEquals("No internet connection — queued until connectivity returns", entry.failureReason)
    }

    // --- Dashboard stats ----------------------------------------------------

    @Test
    fun `stats count each tile independently`() = runTest {
        record("A", MatchType.MATCHED, NotifyStatus.SENT)
        record("B", MatchType.MATCHED, NotifyStatus.SENT)
        record("C", MatchType.UNMATCHED, NotifyStatus.SENT)
        record("D", MatchType.UNMATCHED, NotifyStatus.FAILED)
        record("E", MatchType.MATCHED, NotifyStatus.SILENT) // toggle off
        record("F", MatchType.NO_RULES_CONFIGURED, NotifyStatus.SILENT)

        val stats = repository.statsForToday().first()

        assertEquals("every processed payment counts, whatever happened to it", 6, stats.processed)
        assertEquals(2, stats.matchedNotified)
        assertEquals(1, stats.unmatchedReplied)
        assertEquals(1, stats.failed)
        assertTrue(stats.hasFailures)
    }

    @Test
    fun `a silent payment is processed but not counted as sent`() = runTest {
        // The toggle-off case. It must show in "processed" — the agent needs to
        // see the app noticed the payment — but must not inflate the sent tiles.
        record("A", MatchType.MATCHED, NotifyStatus.SILENT)

        val stats = repository.statsForToday().first()
        assertEquals(1, stats.processed)
        assertEquals(0, stats.matchedNotified)
        assertEquals(0, stats.unmatchedReplied)
        assertFalse(stats.hasFailures)
    }

    @Test
    fun `an empty log reads as zeroes rather than crashing on null sums`() = runTest {
        // SUM() over no rows is NULL in SQLite, not 0 — hence COALESCE in the
        // query. Without it this throws on a fresh install, on the first screen.
        assertEquals(0, repository.statsForToday().first().processed)
        assertFalse(repository.statsForToday().first().hasFailures)
    }

    // --- "Today" means the agent's local day --------------------------------

    @Test
    fun `stats exclude yesterday and tomorrow`() = runTest {
        record("TODAY", MatchType.UNMATCHED, NotifyStatus.SENT, at = now)
        record("YESTERDAY", MatchType.UNMATCHED, NotifyStatus.SENT, at = now.minusSeconds(24 * 60 * 60))
        record("TOMORROW", MatchType.UNMATCHED, NotifyStatus.SENT, at = now.plusSeconds(24 * 60 * 60))

        assertEquals(1, repository.statsForToday().first().processed)
    }

    @Test
    fun `the day boundary is local midnight, not UTC midnight`() = runTest {
        // Both instants fall on 15 July *in UTC*, so a UTC-based boundary would
        // file them under the same day and this test could not tell the two
        // implementations apart. In Nairobi (UTC+3) they straddle local midnight:
        //
        //   21:30Z on the 15th -> 00:30 on the 16th, local  -> today
        //   20:30Z on the 15th -> 23:30 on the 15th, local  -> yesterday
        //
        // Exactly one counts. Getting this wrong would hide the agent's
        // early-morning traffic from the dashboard until 3am local.
        record("JUST_AFTER_LOCAL_MIDNIGHT", MatchType.UNMATCHED, NotifyStatus.SENT, at = Instant.parse("2026-07-15T21:30:00Z"))
        record("JUST_BEFORE_LOCAL_MIDNIGHT", MatchType.UNMATCHED, NotifyStatus.SENT, at = Instant.parse("2026-07-15T20:30:00Z"))

        val today = repository.search(
            since = LocalDate.now(clock).atStartOfDay(nairobi).toInstant().toEpochMilli(),
        ).first()

        assertEquals(1, repository.statsForToday().first().processed)
        assertEquals(listOf("JUST_AFTER_LOCAL_MIDNIGHT"), today.map { it.transactionCode })
    }

    // --- Search / filter ----------------------------------------------------

    @Test
    fun `search matches name, phone and transaction code`() = runTest {
        record("TFA1B2C3D4", MatchType.MATCHED, NotifyStatus.SENT, name = "JOHN KAMAU", phone = "0722000000")
        record("TXB9Z8Y7", MatchType.MATCHED, NotifyStatus.SENT, name = "ALICE WANJIKU", phone = "0733111222")

        assertEquals(1, repository.search(query = "KAMAU").first().size)
        assertEquals(1, repository.search(query = "0733").first().size)
        assertEquals(1, repository.search(query = "TFA1B2").first().size)
        assertEquals(2, repository.search(query = null).first().size)
    }

    @Test
    fun `a blank search box is not a search for empty string`() = runTest {
        record("A", MatchType.MATCHED, NotifyStatus.SENT)

        assertEquals(1, repository.search(query = "   ").first().size)
    }

    @Test
    fun `filters compose`() = runTest {
        record("A", MatchType.MATCHED, NotifyStatus.SENT)
        record("B", MatchType.UNMATCHED, NotifyStatus.SENT)
        record("C", MatchType.UNMATCHED, NotifyStatus.FAILED)

        assertEquals(2, repository.search(matchType = MatchType.UNMATCHED).first().size)
        assertEquals(1, repository.search(notifyStatus = NotifyStatus.FAILED).first().size)
        assertEquals(
            1,
            repository.search(matchType = MatchType.UNMATCHED, notifyStatus = NotifyStatus.SENT).first().size,
        )
        assertEquals(
            0,
            repository.search(matchType = MatchType.MATCHED, notifyStatus = NotifyStatus.FAILED).first().size,
        )
    }

    @Test
    fun `a name containing a quote is data, not SQL`() = runTest {
        record("A", MatchType.MATCHED, NotifyStatus.SENT, name = "O'BRIEN")

        assertEquals(1, repository.search(query = "O'BRIEN").first().size)
    }

    @Test
    fun `the log reads newest first`() = runTest {
        record("OLD", MatchType.MATCHED, NotifyStatus.SENT, at = now.minusSeconds(60))
        record("NEW", MatchType.MATCHED, NotifyStatus.SENT, at = now)

        assertEquals(listOf("NEW", "OLD"), repository.recent.first().map { it.transactionCode })
    }
}
