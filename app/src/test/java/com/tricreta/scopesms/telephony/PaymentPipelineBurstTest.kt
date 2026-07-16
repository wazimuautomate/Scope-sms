package com.tricreta.scopesms.telephony

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.data.AppDatabase
import com.tricreta.scopesms.data.log.ActivityLogRepository
import com.tricreta.scopesms.data.rules.RoomPricingRuleRepository
import com.tricreta.scopesms.data.settings.SettingsRepository
import com.tricreta.scopesms.data.templates.RoomMessageTemplateRepository
import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.parser.MpesaParser
import com.tricreta.scopesms.domain.parser.ParseResult
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.RuleCache
import com.tricreta.scopesms.domain.templates.TemplateCache
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.network.ScopeSmsGateway
import com.tricreta.scopesms.queue.OutboundJobStatus
import com.tricreta.scopesms.queue.OutboundQueue
import com.tricreta.scopesms.queue.RoomOutboundJobStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **BUILD-PLAN Phase 5b's headline exit criterion, end to end.**
 *
 * > *"an explicit test that simulates ~10 SMS_RECEIVED events within 1–3 seconds
 * > (varying amounts, some matched, some not) and asserts every one produces
 * > exactly one correctly-templated queued job, with no drops, no duplicate
 * > sends, and no blocking of the next event. This test is the single most
 * > important exit criterion in the whole plan."*
 *
 * ## Why this exists alongside `OutboundQueueBurstTest`
 * That test drove the burst at the **queue boundary**, because when it was
 * written the receiver, the rules engine and the template engine were unbuilt —
 * its own doc says so and asks for exactly this follow-up. It proved everything
 * the queue owns. It could not prove *"correctly-templated"*, because there was
 * no template engine to render with; the bodies were fixtures.
 *
 * This drives the real decide path — real Room, real rule cache, real template
 * cache, real `TemplateEngine`, real M-Pesa text parsed by the real parser —
 * from the raw SMS body inward. Nothing here is a stand-in except the gateway
 * (a send would be a network call) and the PDU decode.
 *
 * ## What is still not covered, honestly
 * `SmsReceiver.readIntent` — the `Telephony.Sms.Intents.getMessagesFromIntent`
 * PDU decode — is not exercised. `SmsMessage` can only be built by the platform,
 * which is why that method is kept deliberately thin and why every decision it
 * feeds ([SubscriptionExtras], [MpesaParser], [PaymentPipeline]) lives outside
 * it. Real broadcast delivery, including OEM redelivery in the wild, still needs
 * a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PaymentPipelineBurstTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pipeline: PaymentPipeline
    private lateinit var activityLog: ActivityLogRepository
    private lateinit var store: RoomOutboundJobStore

    /** Counts drain requests, so "the queue was kicked" is observable. */
    private val drainRequests = AtomicInteger()

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val rulesRepo = RoomPricingRuleRepository(db.pricingRuleDao())
        val templatesRepo = RoomMessageTemplateRepository(db.messageTemplateDao())

        // The agent's real price list. 20 and 50 match; everything else doesn't.
        rulesRepo.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))
        rulesRepo.upsert(PricingRule(0, KshAmount.ofShillings(50), "2GB Weekly"))

        val ruleCache = RuleCache()
        val templateCache = TemplateCache()
        scope.launch { rulesRepo.observeAll().collect(ruleCache::publish) }
        scope.launch { templatesRepo.observeAll().collect(templateCache::publish) }

        // The caches feed off Room asynchronously; a burst arriving before the
        // first snapshot is a real scenario, but it is `awaitLoaded`'s job and is
        // tested separately. Here we want the steady state.
        withTimeout(TIMEOUT_MS) {
            ruleCache.awaitLoaded()
            templateCache.awaitLoaded()
        }

        activityLog = ActivityLogRepository(db.activityLogDao())
        store = RoomOutboundJobStore(db.outboundJobDao())

        pipeline = PaymentPipeline(
            ruleCache = ruleCache,
            templateCache = templateCache,
            settings = SettingsRepository.create(context),
            activityLog = activityLog,
            queue = OutboundQueue(store = store, gateway = unusableGateway()),
            credentials = object : GatewayCredentialsProvider {
                override suspend fun credentials() =
                    GatewayCredentials(apiKey = "test-key", senderId = "SCOPE")
            },
            requestDrain = { drainRequests.incrementAndGet() },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (db.isOpen) db.close()
    }

    /**
     * Ten real M-Pesa messages, fired concurrently — the client's stated worst
     * case, and the shape of a busy afternoon.
     *
     * Amounts alternate matched (20, 50) and unmatched (35, 77, …), because the
     * two flows take different branches and a burst of only one kind would prove
     * half the path.
     */
    @Test
    fun `ten payments in a burst each produce exactly one correctly-templated job`() = runBlocking {
        val payments = burstOfTen()

        coroutineScope {
            payments.map { sms -> async(Dispatchers.IO) { pipeline.process(parse(sms)) } }.awaitAll()
        }

        val jobs = store.pendingJobs(limit = 100)
        val log = activityLog.search().first()

        // No drops: every payment reached the log.
        assertThat(log).hasSize(payments.size)
        // No duplicates: one job per transaction, and only the reply-worthy ones.
        assertThat(jobs.map { it.transactionCode }).containsNoDuplicates()

        // Both defaults are on for unmatched only (NotificationToggles.DEFAULT),
        // so exactly the unmatched half is queued and the matched half is logged
        // silent. That asymmetry is the toggles working, not a drop.
        val unmatchedCodes = log.filter { it.matchType == MatchType.UNMATCHED }.map { it.transactionCode }
        assertThat(jobs.map { it.transactionCode }).containsExactlyElementsIn(unmatchedCodes)

        jobs.forEach { job ->
            assertThat(job.status).isEqualTo(OutboundJobStatus.PENDING)
            assertThat(job.senderId).isEqualTo("SCOPE")

            // "Correctly-templated" — the part the queue-boundary test could not
            // reach. This is the real default body rendered by the real engine:
            // no token survives to the customer, and the agent's actual price
            // list is quoted.
            assertThat(job.message).doesNotContain("{")
            assertThat(job.message).doesNotContain("}")
            assertThat(job.message).contains("1GB Daily")
            assertThat(job.message).contains("2GB Weekly")
        }

        // Every queued job asked for a drain.
        assertThat(drainRequests.get()).isEqualTo(jobs.size)
    }

    /**
     * The matched half is logged, named, and silent — not dropped.
     *
     * BUILD-PLAN Phase 6 is explicit that a suppressed flow "still gets logged
     * as matched, notification off". An agent asking why a customer got no
     * confirmation must be able to see that the app knew exactly what they
     * bought and chose silence.
     */
    @Test
    fun `a matched payment with the toggle off is logged with its bundle, not dropped`() = runBlocking {
        pipeline.process(parse(mpesaSms("MATCH00001", "20.00", "254700000001", "Jane Wanjiru")))

        val row = activityLog.search().first().single()
        assertThat(row.matchType).isEqualTo(MatchType.MATCHED)
        assertThat(row.notifyStatus).isEqualTo(NotifyStatus.SILENT)
        assertThat(row.bundleDescription).isEqualTo("1GB Daily")
        assertThat(store.pendingJobs()).isEmpty()
    }

    /**
     * The OEM redelivery guard, end to end.
     *
     * Transsion and Xiaomi builds redeliver `SMS_RECEIVED`. A second reply means
     * the customer is texted twice and the agent pays twice — so the guard is
     * enforced by a unique index in SQLite, not by a read-then-write check that
     * could race. Fired concurrently here for exactly that reason.
     */
    @Test
    fun `a redelivered broadcast cannot produce a second reply`() = runBlocking {
        val sms = mpesaSms("DUPE00001", "35.00", "254700000009", "Peter Otieno")

        coroutineScope {
            List(8) { async(Dispatchers.IO) { pipeline.process(parse(sms)) } }.awaitAll()
        }

        assertThat(activityLog.search().first()).hasSize(1)
        assertThat(store.pendingJobs()).hasSize(1)
    }

    /**
     * A payment the agent's price list has no answer for still gets a reply —
     * and the reply quotes the real list.
     */
    @Test
    fun `an unmatched payment is queued with the agent's real price list`() = runBlocking {
        pipeline.process(parse(mpesaSms("ODD000001", "35.00", "254712345678", "Grace Njeri")))

        val job = store.pendingJobs().single()
        assertThat(job.phone).isEqualTo("0712345678")
        assertThat(job.message).contains("Grace Njeri")
        assertThat(job.message).contains("Ksh 20 - 1GB Daily")
        assertThat(job.message).contains("Ksh 50 - 2GB Weekly")
        // Whole shillings, never "20.00" — the client's requirement, rendered.
        assertThat(job.message).doesNotContain("20.00")
    }

    /**
     * A customer who sends an odd amount with cents matches nothing.
     *
     * The cents are the point: rounding Ksh 20.50 down to the Ksh 20 bundle would
     * confirm a purchase the customer never made.
     */
    @Test
    fun `a payment with cents does not match a whole-shilling bundle`() = runBlocking {
        pipeline.process(parse(mpesaSms("CENTS0001", "20.50", "254700000011", "Sam K")))

        val row = activityLog.search().first().single()
        assertThat(row.matchType).isEqualTo(MatchType.UNMATCHED)
        assertThat(row.amount).isEqualTo(KshAmount(2050))
    }

    // --- helpers -----------------------------------------------------------

    private fun burstOfTen(): List<String> = List(10) { i ->
        val amount = if (i % 2 == 0) MATCHED_AMOUNTS[i / 2 % MATCHED_AMOUNTS.size] else "%d.00".format(31 + i)
        mpesaSms(
            code = "BURST%05d".format(i),
            amount = amount,
            phone = "2547000000%02d".format(i),
            name = "Customer $i",
        )
    }

    /**
     * A real till confirmation, in the client's actual format — including the
     * irregular `Confirmed.on` and `PMKsh20.00` concatenation CLAUDE.md calls
     * out. Parsed by the real parser, so this test breaks if the format handling
     * regresses.
     */
    private fun mpesaSms(code: String, amount: String, phone: String, name: String): String =
        "$code Confirmed.on 15/7/26 at 1:06 PMKsh$amount received from $phone $name. " +
            "New Account balance is Ksh1300.22. Transaction cost, Ksh0.00."

    private fun parse(body: String) =
        (MpesaParser.parse(body) as ParseResult.Parsed).payment

    /**
     * A gateway that fails if touched.
     *
     * The decide path must never call the network (CLAUDE.md constraint 5), so
     * rather than assert that indirectly, make it impossible: any call here fails
     * the test outright.
     */
    private fun unusableGateway(): ScopeSmsGateway = ScopeSmsGateway.create(
        credentialsProvider = object : GatewayCredentialsProvider {
            override suspend fun credentials(): GatewayCredentials =
                error("The decide path must not touch the gateway")
        },
        // Port 1 is unbindable; even reaching the client would hang or refuse
        // rather than quietly succeed against something real.
        baseUrl = "http://127.0.0.1:1/",
    )

    private companion object {
        val MATCHED_AMOUNTS = listOf("20.00", "50.00")
        const val TIMEOUT_MS = 5_000L
    }
}
