package com.agronomy.phytomonitor.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agronomy.phytomonitor.journal.JournalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = SyncSessionStore.get(applicationContext)
        val session = store.session.value ?: return@withContext Result.success()
        val coroutine = currentCoroutineContext()
        SyncWork.started(session.account)
        try {
            val active = {
                coroutine.isActive && !isStopped && store.session.value?.generation == session.generation
            }
            val engine = SyncWork.engine(applicationContext)
            val retry = engine.synchronize(session.account, SyncHttpClient(session.origin, session.token, active), active)
            if (engine.authenticationRequired) store.expire(session.generation) { SyncWork.cancel(applicationContext) }
            if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            if (!coroutine.isActive || isStopped || Thread.currentThread().isInterrupted ||
                store.session.value?.generation == session.generation) throw cancelled
            // Session turnover ends this run, not the WorkManager chain. Cancelling this
            // predecessor would also cancel the already appended work for the new login.
            Result.success()
        } finally {
            SyncWork.finished(session.account)
        }
    }
}

object SyncWork {
    private const val WORK = "agrovoice_sync"
    private const val PERIODIC = "agrovoice_sync_periodic"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val mutableRunning = MutableStateFlow<Map<String, Int>>(emptyMap())
    val running: StateFlow<Map<String, Int>> = mutableRunning

    @Synchronized internal fun started(account: String) {
        mutableRunning.value = mutableRunning.value + (account to ((mutableRunning.value[account] ?: 0) + 1))
    }

    @Synchronized internal fun finished(account: String) {
        val remaining = (mutableRunning.value[account] ?: 1) - 1
        mutableRunning.value = if (remaining == 0) mutableRunning.value - account else mutableRunning.value + (account to remaining)
    }

    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).result.get()
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun engine(context: Context): SyncEngine {
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { File(it, "photos").canonicalFile }
        return SyncEngine(JournalDatabase.get(context), { file ->
            roots.any { root -> file.toPath().startsWith(root.toPath()) }
        })
    }
}
