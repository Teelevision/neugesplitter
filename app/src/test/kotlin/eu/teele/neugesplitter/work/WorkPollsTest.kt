package eu.teele.neugesplitter.work

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import eu.teele.neugesplitter.domain.value.GroupId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkPollsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val trip = GroupId.parse("%032x".format(9))

    @Before
    fun initialise() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun info() =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("poll-${trip.hex}")
            .get()
            .single()

    @Test
    fun `a group's poll repeats at its latest interval while online, until cancelled (TR-9, SY-13)`() {
        val polls = WorkPolls(context)
        polls.schedule(trip, 6.hours)
        polls.schedule(trip, 1.hours)

        val info = info()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(1.hours.inWholeMilliseconds, info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        polls.cancel(trip)
        assertEquals(WorkInfo.State.CANCELLED, info().state)
    }

    @Test
    fun `a poll of a group this device no longer shares succeeds and is not scheduled again`() {
        WorkPolls(context).schedule(trip, 6.hours)
        val worker =
            TestListenableWorkerBuilder<PollWorker>(context)
                .setInputData(workDataOf(WorkPolls.GROUP to trip.hex))
                .build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertEquals(WorkInfo.State.CANCELLED, info().state)
    }
}
