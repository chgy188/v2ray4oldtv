package com.v2ray.ang.tv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 每个 TV Activity 自己拥有一个可取消的后台作用域，对应 :tv 模块的 UiScope。 */
class UiScope {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(true)

    fun <T> launch(work: () -> T, onResult: (Result<T>) -> Unit) {
        if (!alive.get()) return
        executor.execute {
            val result = runCatching(work)
            main.post {
                if (alive.get()) onResult(result)
            }
        }
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        main.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
