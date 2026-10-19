package com.agronomy.phytomonitor.ml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Owns the detector's serial I/O lifetime. close() rejects new work and drains accepted operations,
 * including an already requested journal save, before closing the native session exactly once.
 */
internal class SerialModelWork(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onFailure: suspend (Exception) -> Unit,
    private val onClosed: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val job = scope.launch {
        try {
            requests.consumeAsFlow().collect { operation ->
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(error)
                }
            }
        } finally {
            try {
                onClosed()
            } finally {
                scope.cancel()
            }
        }
    }

    fun submit(operation: suspend () -> Unit) {
        check(requests.trySend(operation).isSuccess) { "Model work has already been closed" }
    }

    fun close() { requests.close() }
    suspend fun awaitClosed() { job.join() }
}
