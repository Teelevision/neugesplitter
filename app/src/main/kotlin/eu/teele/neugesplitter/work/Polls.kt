package eu.teele.neugesplitter.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.teele.neugesplitter.Neugesplitter
import eu.teele.neugesplitter.domain.value.GroupId
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Each shared group's background poll, run no more often than the group asks (TR-9, SY-13). */
interface Polls {

    /** Polls group [id] at most every [interval], replacing whatever was scheduled for it. */
    fun schedule(id: GroupId, interval: Duration)

    fun cancel(id: GroupId)

    fun cancelAll()
}

/** [Polls] as WorkManager runs them: whenever the system lets them, never sooner (TR-9). */
class WorkPolls(private val context: Context) : Polls {

    private val work by lazy { WorkManager.getInstance(context) }

    override fun schedule(id: GroupId, interval: Duration) {
        val every = interval.toJavaDuration()
        val request =
            PeriodicWorkRequestBuilder<PollWorker>(every)
                // Connecting or opening the app has just passed over the group.
                .setInitialDelay(every)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(GROUP to id.hex))
                .addTag(TAG)
                .build()
        work.enqueueUniquePeriodicWork(name(id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel(id: GroupId) {
        work.cancelUniqueWork(name(id))
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    internal companion object {
        const val GROUP = "group"
        const val TAG = "poll"

        fun name(id: GroupId) = "poll-${id.hex}"
    }
}

/** One background poll: a pass over one group, which reports how it went itself (SY-6). */
class PollWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val id =
            inputData.getString(WorkPolls.GROUP)?.let(GroupId::parse) ?: return Result.failure()
        (applicationContext as Neugesplitter).graph.syncing.poll(id)
        return Result.success()
    }
}
