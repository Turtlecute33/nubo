package app.nubo.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore

/**
 * Runs [work] on all cores for each value of [add], while the caller still reads its input: the drive tree comes
 * from the network or the disk while the first parts decrypt (review P-01). At most [IN_FLIGHT] values wait or run
 * at a time, so a fast reader does not fill the memory. [results] keeps the order of [add]. A [work] that throws
 * cancels [scope].
 */
class Parallel<T, R>(private val scope: CoroutineScope, private val work: (T) -> R) {
    private val gate = Semaphore(IN_FLIGHT)
    private val parts = ArrayList<Deferred<R>>()

    suspend fun add(value: T) {
        gate.acquire()
        parts += scope.async(Dispatchers.Default) {
            try {
                work(value)
            } finally {
                gate.release()
            }
        }
    }

    suspend fun results(): List<R> = parts.awaitAll()

    private companion object {
        val IN_FLIGHT = Runtime.getRuntime().availableProcessors() * 2
    }
}
