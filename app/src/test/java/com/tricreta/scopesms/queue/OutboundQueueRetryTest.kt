package com.tricreta.scopesms.queue

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.network.BlazeTechGateway
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.network.GatewayProvider
import com.tricreta.scopesms.network.GatewayRegistry
import com.tricreta.scopesms.network.ScopeSmsApi
import com.tricreta.scopesms.network.SendOutcome
import com.tricreta.scopesms.network.SendSmsRequest
import com.tricreta.scopesms.network.SendSmsResponse
import com.tricreta.scopesms.network.SmsGateway
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response

/**
 * The queue's failure paths. `queue/README.md`: *"Silence is the one
 * unacceptable outcome; the agent's customer is waiting on that SMS."*
 *
 * Each test below is one way a message could disappear quietly.
 */
class OutboundQueueRetryTest {

    private val credentials = GatewayCredentials(apiKey = "k", senderId = "SCOPE SMS")

    @Test
    fun `a failed send is recorded terminally under send-once, never left pending`() = runTest {
        // Send-once (2026-07-19): the client was being re-billed for retried
        // sends, so a failure is now terminal — FAILED with its reason — rather
        // than re-queued. The reason is what shows in the activity log.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { errorResponse(500) })
        enqueueOne(queue)

        val summary = queue.drain()

        assertThat(summary.failed).isEqualTo(1)
        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(job.attemptCount).isEqualTo(1)
        assertThat(job.lastError).contains("server error")
    }

    @Test
    fun `a connectivity drop while sending fails the job under send-once`() = runTest {
        // Under send-once a drop *while sending* is terminal, not a retry: we
        // can't tell whether the SMS reached the gateway first, so re-sending
        // could double-charge. (The offline-ARRIVAL case is separate and
        // unaffected — WorkManager's NetworkType.CONNECTED constraint holds an
        // unclaimed job PENDING until data returns, so it is still sent once.)
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { throw IOException("no route to host") })
        enqueueOne(queue)

        queue.drain()

        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(job.lastError).contains("No internet connection")
    }

    @Test
    fun `a failed send is not retried on a later drain, so the gateway is hit once`() = runTest {
        // The token-bleed guard: one 429 must not become a second billed attempt.
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val api = Responds { attempts.incrementAndGet(); errorResponse(429) }
        val queue = queueOf(store, api)
        enqueueOne(queue)

        val first = queue.drain()
        val second = queue.drain()

        assertThat(first.failed).isEqualTo(1)
        // FAILED after one attempt; a later drain must not pick it up again.
        assertThat(second.processed).isEqualTo(0)
        assertThat(attempts.get()).isEqualTo(1)
        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(job.attemptCount).isEqualTo(1)
    }

    @Test
    fun `a send hits the gateway exactly once across many drains`() = runTest {
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val queue = queueOf(store, Responds { attempts.incrementAndGet(); errorResponse(500) })
        enqueueOne(queue)

        // Drain repeatedly; send-once must still only reach the gateway one time.
        repeat(5) { queue.drain() }

        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(job.attemptCount).isEqualTo(1)
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(job.lastError).contains("server error")
    }

    @Test
    fun `a terminal failure fails immediately without burning retries`() = runTest {
        // An unregistered sender ID will never succeed. network/README.md:
        // surface it to the agent "instead of burning the queue against it
        // forever".
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val queue = queueOf(
            store,
            Responds {
                attempts.incrementAndGet()
                errorResponse(403, """{"message":"Sender ID not registered"}""")
            },
        )
        enqueueOne(queue)

        repeat(3) { queue.drain() }

        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(job.lastError).contains("Sender ID not registered")
    }

    @Test
    fun `a job stranded mid-send is failed, not resent, under send-once`() = runTest {
        // The other half of send-once: a kill mid-send may have happened *after*
        // the SMS went out, so re-sending would double-charge. releaseStuckJobs
        // returns the row to PENDING, but its one attempt is already spent, so the
        // next drain fails it instead of calling the gateway again. The agent
        // Force-sends by hand if the customer truly never received it.
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val queue = queueOf(store, Responds { attempts.incrementAndGet(); accepted() })
        enqueueOne(queue)
        val id = store.allJobs().single().id
        store.markSending(id) // simulate the crash: claimed, attempt burned, never returned

        val summary = queue.drain()

        assertThat(summary.failed).isEqualTo(1)
        assertThat(attempts.get()).isEqualTo(0) // the gateway is NOT called again
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.FAILED)
    }

    @Test
    fun `claiming a job burns an attempt, so a send cancelled mid-flight cannot retry forever`() = runTest {
        // The bug this guards: markSending used to only set the status, and the
        // attempt was counted when the gateway answered. A send cancelled before
        // it answered — WorkManager stopping the worker, the window expiring,
        // process death, all routine on 2G — left attemptCount at 0, so
        // releaseStuckJobs re-queued it and it re-sent the same SMS forever, the
        // customer texted and the agent charged each time. Counting at claim time
        // makes the budget bound the damage however the attempt ends.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { accepted() })
        enqueueOne(queue)
        val id = store.allJobs().single().id

        // Three process deaths mid-send: claim, then die before the reply.
        repeat(3) {
            store.markSending(id)
            store.releaseStuckJobs()
        }

        assertThat(store.allJobs().single().attemptCount).isEqualTo(3)
    }

    @Test
    fun `a job queued while a drain is running is sent by that same drain`() = runTest {
        // The burst tail — the crux of the "stays on Sending…" report. enqueueDrain
        // uses ExistingWorkPolicy.KEEP, so a payment that lands while a drain is
        // already in flight has its own drain re-trigger dropped. The old drain
        // read the pending list exactly once, so that late row was invisible to it
        // and sat PENDING until an unrelated SMS or an app restart — which, on a
        // quiet tail of a burst, might never come. One drain must now finish the
        // whole burst, including rows queued after it began.
        val store = FakeOutboundJobStore()
        lateinit var queue: OutboundQueue
        val calls = AtomicInteger(0)
        val api = Responds {
            // A second payment arrives while the first is mid-send.
            if (calls.incrementAndGet() == 1) {
                queue.enqueue(
                    "TX-LATE",
                    "254700000099",
                    "Hi, prices: 20=1GB",
                    credentials.senderId,
                    GatewayProvider.BLAZETECH,
                )
            }
            accepted()
        }
        queue = queueOf(store, api)
        enqueueOne(queue)

        val summary = queue.drain()

        assertThat(summary.sent).isEqualTo(2)
        assertThat(store.allJobs()).hasSize(2)
        assertThat(store.allJobs().map { it.status }.toSet())
            .containsExactly(OutboundJobStatus.SENT)
    }

    @Test
    fun `one failing job does not strand the jobs behind it`() = runTest {
        val store = FakeOutboundJobStore()
        val calls = AtomicInteger(0)
        val queue = queueOf(
            store,
            // The first job in the queue fails terminally; the rest must still go.
            Responds { if (calls.incrementAndGet() == 1) errorResponse(401) else accepted() },
        )
        repeat(3) { enqueueOne(queue, code = "TX$it", phone = "07000000%02d".format(it)) }

        val summary = queue.drain()

        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.sent).isEqualTo(2)
        assertThat(summary.processed).isEqualTo(3)
    }

    // --- Force send (manual, bypasses the queue and send-once) --------------

    @Test
    fun `forceSend sends a queued job immediately and marks it sent`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { accepted() })
        enqueueOne(queue)

        val result = queue.forceSend("TX00000001")

        assertThat(result).isEqualTo(ForceSendResult.Sent)
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.SENT)
    }

    @Test
    fun `forceSend re-sends a failed job, bypassing the send-once guard`() = runTest {
        // A message the queue gave up on can still be pushed by hand.
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val queue = queueOf(
            store,
            Responds { if (attempts.incrementAndGet() == 1) errorResponse(500) else accepted() },
        )
        enqueueOne(queue)
        queue.drain() // first attempt fails terminally under send-once
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.FAILED)

        val result = queue.forceSend("TX00000001")

        assertThat(result).isEqualTo(ForceSendResult.Sent)
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.SENT)
        assertThat(attempts.get()).isEqualTo(2) // drain once + forceSend once
    }

    @Test
    fun `forceSend returns NoJob when nothing is queued for that transaction`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { accepted() })

        assertThat(queue.forceSend("TX-UNKNOWN")).isEqualTo(ForceSendResult.NoJob)
    }

    @Test
    fun `forceSend reports the gateway's reason on failure`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(
            store,
            Responds { errorResponse(403, """{"message":"Sender ID not registered"}""") },
        )
        enqueueOne(queue)

        val result = queue.forceSend("TX00000001")

        assertThat(result).isInstanceOf(ForceSendResult.Failed::class.java)
        assertThat((result as ForceSendResult.Failed).reason).contains("Sender ID not registered")
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.FAILED)
    }

    @Test
    fun `cancelPending deletes pending and sending jobs but keeps terminal ones`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { accepted() })
        // One SENT (drained), one left PENDING, one stuck SENDING.
        enqueueOne(queue, code = "TX-SENT", phone = "254700000001")
        queue.drain()
        enqueueOne(queue, code = "TX-PENDING", phone = "254700000002")
        enqueueOne(queue, code = "TX-SENDING", phone = "254700000003")
        store.markSending(store.jobByTransactionCode("TX-SENDING")!!.id)

        val cancelled = queue.cancelPending()

        assertThat(cancelled).isEqualTo(2)
        val remaining = store.allJobs()
        assertThat(remaining.map { it.transactionCode }).containsExactly("TX-SENT")
        assertThat(remaining.single().status).isEqualTo(OutboundJobStatus.SENT)
    }

    // --- Routing: a job sends via ITS OWN captured provider, not "whichever" --

    @Test
    fun `a job enqueued under HOSTPINNACLE sends via HostPinnacle, and one under BLAZETECH via BlazeTech`() = runTest {
        val store = FakeOutboundJobStore()
        val blazeTechCalls = AtomicInteger(0)
        val hostPinnacleCalls = AtomicInteger(0)
        val queue = OutboundQueue(
            store = store,
            gateways = GatewayRegistry(
                blazeTech = RecordingGateway(blazeTechCalls),
                hostPinnacle = RecordingGateway(hostPinnacleCalls),
            ),
            now = { 1_700_000_000_000L },
        )
        enqueueOne(queue, code = "TX-BT", phone = "254700000001", provider = GatewayProvider.BLAZETECH)
        enqueueOne(queue, code = "TX-HP", phone = "254700000002", provider = GatewayProvider.HOSTPINNACLE)

        val summary = queue.drain()

        assertThat(summary.sent).isEqualTo(2)
        assertThat(blazeTechCalls.get()).isEqualTo(1)
        assertThat(hostPinnacleCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a legacy job with a null provider sends via BlazeTech, not HostPinnacle`() = runTest {
        // Simulates every job queued before the provider column existed —
        // inserted directly, bypassing enqueue() (which always stamps one now).
        // GatewayProvider.fromName(null) must decode to BLAZETECH.
        val store = FakeOutboundJobStore()
        val blazeTechCalls = AtomicInteger(0)
        val hostPinnacleCalls = AtomicInteger(0)
        val queue = OutboundQueue(
            store = store,
            gateways = GatewayRegistry(
                blazeTech = RecordingGateway(blazeTechCalls),
                hostPinnacle = RecordingGateway(hostPinnacleCalls),
            ),
            now = { 1_700_000_000_000L },
        )
        store.insertIfNew(
            OutboundJob(
                transactionCode = "TX-LEGACY",
                phone = "254700000003",
                message = "Hi",
                senderId = credentials.senderId,
                createdAt = 1_700_000_000_000L,
                provider = null,
            ),
        )

        queue.drain()

        assertThat(blazeTechCalls.get()).isEqualTo(1)
        assertThat(hostPinnacleCalls.get()).isEqualTo(0)
    }

    /** Records how many times it was called and always accepts — for routing proofs. */
    private class RecordingGateway(private val calls: AtomicInteger) : SmsGateway {
        override suspend fun sendSms(phone: String, message: String, senderId: String?): SendOutcome {
            calls.incrementAndGet()
            return SendOutcome.Sent(messageId = "msg-${calls.get()}", mobile = phone, networkId = null)
        }
    }

    private suspend fun enqueueOne(
        queue: OutboundQueue,
        code: String = "TX00000001",
        phone: String = "254700000001",
        provider: GatewayProvider = GatewayProvider.BLAZETECH,
    ) = queue.enqueue(code, phone, "Hi Bonke, prices: 20=1GB", credentials.senderId, provider)

    private fun queueOf(store: OutboundJobStore, api: ScopeSmsApi) = OutboundQueue(
        store = store,
        gateways = GatewayRegistry(
            blazeTech = BlazeTechGateway(
                api,
                object : GatewayCredentialsProvider {
                    override suspend fun credentials() = credentials
                },
            ),
            // None of the tests in this file enqueue under HOSTPINNACLE, so a
            // gateway that fails loudly if ever reached would catch a routing
            // bug rather than silently passing.
            hostPinnacle = UnreachableGateway,
        ),
        now = { 1_700_000_000_000L },
    )

    /** Fails loudly if a job ever gets routed to the "wrong" provider's gateway. */
    private object UnreachableGateway : SmsGateway {
        override suspend fun sendSms(phone: String, message: String, senderId: String?): SendOutcome =
            error("This job should never have been routed to this gateway")
    }

    private fun accepted() = Response.success(
        SendSmsResponse(
            responseCode = 200,
            messageId = "msg-1",
            mobile = "0700000001",
            networkId = "1",
            message = null,
        ),
    )

    private fun errorResponse(code: Int, body: String = "{}"): Response<SendSmsResponse> =
        Response.error(code, body.toResponseBody("application/json".toMediaType()))

    private class Responds(
        private val block: suspend () -> Response<SendSmsResponse>,
    ) : ScopeSmsApi {
        override suspend fun sendSms(request: SendSmsRequest) = block()
    }
}
