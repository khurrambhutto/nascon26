package com.nascon.league.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.nascon.league.data.ServiceLocator
import kotlinx.coroutines.*

/** Native persisted fallback when the UI is no longer running. No BaaS or extra SDK. */
class QueueSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val success = try {
                withTimeout(25_000) {
                    val repo = ServiceLocator.repo
                    repo.probe()
                    repo.serverReachable.value && repo.syncAllPending()
                }
            } catch (_: TimeoutCancellationException) { false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val ID = 20801
        fun schedule(context: Context, needed: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!needed) { scheduler.cancel(ID); return }
            // Don't replace an already scheduled/running job on every queue refresh.
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, QueueSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
