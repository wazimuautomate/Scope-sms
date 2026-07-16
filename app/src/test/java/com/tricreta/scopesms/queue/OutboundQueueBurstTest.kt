package com.tricreta.scopesms.queue

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.network.ScopeSmsApi
import com.tricreta.scopesms.network.ScopeSmsGateway
import com.tricreta.scopesms.network.SendSmsRequest
import com.tricreta.scopesms.network.SendSmsResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import retrofit2.Response

/**
 * **Phase 5b's headline exit criterion.**
 *
 * BUILD-PLAN: *"an explicit test that simulates ~10 SMS_RECEIVED events within
 * 1–3 seconds (varying amounts, some matched, some not) and asserts every one
 * produces exactly one correctly-templated queued job, with no drops, no
 * duplicate sends, and no blocking of the next event. This test is the single
 * most important exit criterion in the whole plan."*
 *
 * ### What this covers, and what it can't yet
 *
 * The burst is driven at the **queue boundary** — the point the receiver's
 * decide path calls — rather than through a real `SMS_RECEIVED` broadcast,
 * because the receiver (Phase 2), the rules engine (Phase 3) and the template
 * engine (Phase 4) are being built in parallel sessions and don't exist yet.
 * Every guarantee the criterion names that the queue actually owns is proven
 * here: no drops, no duplicates, no blocking, exactly one job per payment.
 *
 * Two parts genuinely need the other phases and are **not** covered:
 *  - *"correctly-templated"* — bodies here are fixtures standing in for Phase 4's
 *    renderer. Verified as "the body handed to enqueue is the body queued,
 *    unchanged", which is the queue's half of that contract.
 *  - Real `SMS_RECEIVED` delivery, including OEM redelivery in the wild.
 *
 * When Phases 2–4 land, this must be re-run end-to-end from the receiver. See
 * memory.md — Phase 5b is not fully signed off until that happens.
 */
class OutboundQueueBurstTest {

    private val credentials = GatewayCredentials(apiKey = "k", senderId = "SCOPE SMS")

    /**
     * Ten payments landing at once — the client's stated worst case. Mixed
     * matched/unmatched, mirroring how the two flows (purchase confirmation vs.
     * price list) both feed this queue.
     */
    private val burst = List(10) { i ->
        val matched = i % 3 == 0
        Payment(
            transactionCode = "TX%08d".format(i),
            phone = "2547000000%02d".format(i),
            message = if (matched) {
                "Thank you Customer$i for purchasing 1GB Daily. Karibu tena."
            } else {
                "Hi Customer$i, Ksh${20 + i} matches no bundle. Prices: 20=1GB, 50=3GB."
            },
        )
    }

    private data class Payment(val transactionCode: String, val phone: String, val message: String)

    // --- No drops -----------------------------------------------------------

    @Test
    fun `ten payments arriving together each produce exactly one queued job`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, api = AlwaysAccepts())

        // Dispatchers.Default so these genuinely run in parallel on multiple
        // threads. A single-threaded scheduler would serialise them and the
        // race this is built to catch could never occur.
        val results = withContext(Dispatchers.Default) {
            coroutineScope {
                burst.map { payment -> async { enqueue(queue, payment) } }.awaitAll()
            }
        }

        assertThat(results.filterIsInstance<OutboundQueue.EnqueueResult.Queued>()).hasSize(10)

        val jobs = store.allJobs()
        assertThat(jobs).hasSize(10)
        // Every payment present, none lost to the race.
        assertThat(jobs.map { it.transactionCode })
            .containsExactlyElementsIn(burst.map { it.transactionCode })
        assertThat(jobs.map { it.status }.toSet()).containsExactly(OutboundJobStatus.PENDING)
    }

    @Test
    fun `the queued body is the body the decide path handed over, unchanged`() = runTest {
        // The queue's half of "correctly-templated": Phase 4 renders, and the
        // queue must not touch the result. Stored per-job rather than re-rendered
        // at send time, so editing a template mid-burst can't rewrite a message
        // the customer is already owed.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, api = AlwaysAccepts())

        burst.forEach { enqueue(queue, it) }

        val byCode = store.allJobs().associateBy { it.transactionCode }
        burst.forEach { payment ->
            val job = byCode.getValue(payment.transactionCode)
            assertThat(job.message).isEqualTo(payment.message)
            assertThat(job.phone).isEqualTo(payment.phone)
            assertThat(job.senderId).isEqualTo("SCOPE SMS")
        }
    }

    // --- No duplicate sends -------------------------------------------------

    @Test
    fun `a redelivered broadcast does not queue a second reply`() = runTest {
        // queue/README.md: some OEMs redeliver SMS_RECEIVED, and "a duplicate
        // costs the agent money and annoys a customer".
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, api = AlwaysAccepts())
        val payment = burst.first()

        val first = enqueue(queue, payment)
        val second = enqueue(queue, payment)

        assertThat(first).isInstanceOf(OutboundQueue.EnqueueResult.Queued::class.java)
        assertThat(second).isEqualTo(OutboundQueue.EnqueueResult.Duplicate)
        assertThat(store.allJobs()).hasSize(1)
    }

    @Test
    fun `simultaneous redelivery of one payment still produces exactly one job`() = runTest {
        // The nastier version: two deliveries of the same transaction racing.
        // A check-then-insert in application code passes the sequential test
        // above and fails this one — both callers see "no row" before either
        // writes. Hence the unique index rather than a Kotlin-side guard.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, api = AlwaysAccepts())
        val payment = burst.first()

        val results = withContext(Dispatchers.Default) {
            coroutineScope {
                List(8) { async { enqueue(queue, payment) } }.awaitAll()
            }
        }

        assertThat(store.allJobs()).hasSize(1)
        assertThat(results.count { it is OutboundQueue.EnqueueResult.Queued }).isEqualTo(1)
        assertThat(results.count { it is OutboundQueue.EnqueueResult.Duplicate }).isEqualTo(7)
        // All eight attempts reached storage — the guard is the index, not luck.
        assertThat(store.insertAttempts).isEqualTo(8)
    }

    @Test
    fun `a full burst drains to exactly one gateway call per payment`() = runTest {
        val store = FakeOutboundJobStore()
        val api = AlwaysAccepts()
        val queue = queueOf(store, api)

        // Every payment delivered twice, as an aggressive OEM would.
        burst.forEach { enqueue(queue, it) }
        burst.forEach { enqueue(queue, it) }

        val summary = queue.drain()

        assertThat(summary.sent).isEqualTo(10)
        assertThat(api.requests).hasSize(10)
        assertThat(api.requests.map { it.phone }.toSet()).hasSize(10)
        assertThat(store.allJobs().map { it.status }.toSet())
            .containsExactly(OutboundJobStatus.SENT)
        // Every job carries its gateway id — the handle Phase 8's log needs.
        assertThat(store.allJobs().all { it.gatewayMessageId != null }).isTrue()
    }

    // --- No blocking of the next event --------------------------------------

    @Test
    fun `enqueue never touches the network`() = runTest {
        // The decoupling, asserted structurally rather than by timing — this is
        // CLAUDE.md constraint 5's "no network, no disk write, before the
        // decision is made", and it can't flake.
        val store = FakeOutboundJobStore()
        val api = AlwaysAccepts()
        val queue = queueOf(store, api)

        burst.forEach { enqueue(queue, it) }

        assertThat(api.requests).isEmpty()
        assertThat(store.allJobs()).hasSize(10)
    }

    @Test
    fun `a hung gateway does not delay the burst`() = runTest {
        // The failure this exists to catch: if the receiver awaited the send,
        // one stalled request would stall every payment behind it and the agent
        // would lose the customers in that window. The gateway here takes 30s
        // per call; ten enqueues must still finish immediately.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, api = Hangs(delayMillis = 30_000))

        val startedAt = System.nanoTime()
        withContext(Dispatchers.Default) {
            coroutineScope {
                burst.map { payment -> async { enqueue(queue, payment) } }.awaitAll()
            }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(store.allJobs()).hasSize(10)
        // Real work is microseconds. The 1s bound is slack for a loaded CI
        // runner, and still 30x under a single blocking call — enough to fail
        // loudly if anyone ever awaits the send from the decide path.
        assertThat(elapsedMillis).isLessThan(1_000)
    }

    private suspend fun enqueue(queue: OutboundQueue, payment: Payment) = queue.enqueue(
        transactionCode = payment.transactionCode,
        phone = payment.phone,
        message = payment.message,
        senderId = credentials.senderId,
    )

    private fun queueOf(store: OutboundJobStore, api: ScopeSmsApi) = OutboundQueue(
        store = store,
        gateway = ScopeSmsGateway(api, provider()),
        now = MonotonicClock()::next,
    )

    private fun provider() = object : GatewayCredentialsProvider {
        override suspend fun credentials() = credentials
    }

    /**
     * Distinct, ordered timestamps without touching the wall clock — ten SMS in
     * a burst can otherwise share a millisecond, making "oldest first" ambiguous
     * and the drain order untestable.
     */
    private class MonotonicClock {
        private val tick = AtomicInteger(0)
        fun next(): Long = 1_700_000_000_000L + tick.getAndIncrement()
    }

    private class AlwaysAccepts : ScopeSmsApi {
        val requests = CopyOnWriteArrayList<SendSmsRequest>()
        private val ids = AtomicInteger(0)

        override suspend fun sendSms(request: SendSmsRequest): Response<SendSmsResponse> {
            requests += request
            return Response.success(
                SendSmsResponse(
                    responseCode = 200,
                    messageId = "msg-${ids.incrementAndGet()}",
                    mobile = request.phone,
                    networkId = "1",
                    message = null,
                ),
            )
        }
    }

    private class Hangs(private val delayMillis: Long) : ScopeSmsApi {
        override suspend fun sendSms(request: SendSmsRequest): Response<SendSmsResponse> {
            delay(delayMillis)
            error("unreachable in these tests")
        }
    }
}
