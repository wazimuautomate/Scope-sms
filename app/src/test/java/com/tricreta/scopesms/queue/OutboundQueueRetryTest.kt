package com.tricreta.scopesms.queue

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.network.ScopeSmsApi
import com.tricreta.scopesms.network.ScopeSmsGateway
import com.tricreta.scopesms.network.SendSmsRequest
import com.tricreta.scopesms.network.SendSmsResponse
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
    fun `a retryable failure leaves the job pending rather than dropping it`() = runTest {
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { errorResponse(500) })
        enqueueOne(queue)

        val summary = queue.drain()

        assertThat(summary.retryable).isEqualTo(1)
        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.PENDING)
        assertThat(job.attemptCount).isEqualTo(1)
        // The reason is recorded even mid-retry, so a stuck queue is diagnosable.
        assertThat(job.lastError).contains("server error")
    }

    @Test
    fun `no connectivity holds the job for later, never fails it`() = runTest {
        // CLAUDE.md constraint 2's core case: a payment arrives while the phone
        // has no data. WorkManager's NetworkType.CONNECTED constraint then runs
        // the drain once connectivity is back.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { throw IOException("no route to host") })
        enqueueOne(queue)

        queue.drain()

        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.PENDING)
        assertThat(job.lastError).contains("No internet connection")
    }

    @Test
    fun `a transient failure followed by success sends exactly once`() = runTest {
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val api = Responds {
            if (attempts.incrementAndGet() == 1) errorResponse(429) else accepted()
        }
        val queue = queueOf(store, api)
        enqueueOne(queue)

        val first = queue.drain()
        val second = queue.drain()

        assertThat(first.retryable).isEqualTo(1)
        assertThat(second.sent).isEqualTo(1)
        assertThat(attempts.get()).isEqualTo(2)
        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.SENT)
        // Cleared on success — a SENT job showing a stale error would read as a
        // failure in the activity log.
        assertThat(job.lastError).isNull()
    }

    @Test
    fun `retries are bounded and end in a readable failure`() = runTest {
        val store = FakeOutboundJobStore()
        val attempts = AtomicInteger(0)
        val queue = queueOf(store, Responds { attempts.incrementAndGet(); errorResponse(500) })
        enqueueOne(queue)

        // Drain more times than the budget allows.
        repeat(OutboundQueue.DEFAULT_MAX_ATTEMPTS + 3) { queue.drain() }

        val job = store.allJobs().single()
        assertThat(job.status).isEqualTo(OutboundJobStatus.FAILED)
        assertThat(job.attemptCount).isEqualTo(OutboundQueue.DEFAULT_MAX_ATTEMPTS)
        // Stops calling the gateway once the budget is spent — a FAILED job must
        // not be picked up again by later drains.
        assertThat(attempts.get()).isEqualTo(OutboundQueue.DEFAULT_MAX_ATTEMPTS)
        assertThat(job.lastError).contains("gave up after 5 attempts")
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
    fun `a job stranded by process death is reclaimed on the next drain`() = runTest {
        // The invisible drop: SENDING is set immediately before the HTTP call,
        // so a kill mid-send (routine on these devices) leaves a job that is
        // never sent, never retried and never reported.
        val store = FakeOutboundJobStore()
        val queue = queueOf(store, Responds { accepted() })
        enqueueOne(queue)
        val id = store.allJobs().single().id
        store.markSending(id) // simulate the crash

        val summary = queue.drain()

        assertThat(summary.sent).isEqualTo(1)
        assertThat(store.allJobs().single().status).isEqualTo(OutboundJobStatus.SENT)
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
                queue.enqueue("TX-LATE", "254700000099", "Hi, prices: 20=1GB", credentials.senderId)
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

    private suspend fun enqueueOne(
        queue: OutboundQueue,
        code: String = "TX00000001",
        phone: String = "254700000001",
    ) = queue.enqueue(code, phone, "Hi Bonke, prices: 20=1GB", credentials.senderId)

    private fun queueOf(store: OutboundJobStore, api: ScopeSmsApi) = OutboundQueue(
        store = store,
        gateway = ScopeSmsGateway(
            api,
            object : GatewayCredentialsProvider {
                override suspend fun credentials() = credentials
            },
        ),
        now = { 1_700_000_000_000L },
    )

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
