package dev.auredial.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import dev.auredial.domain.Decision
import dev.auredial.domain.evaluate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreeningService : CallScreeningService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val completed = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + 1200L
        fun respond(block: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            val response = CallResponse.Builder().setDisallowCall(block).setRejectCall(block)
                .setSkipCallLog(false).setSkipNotification(false).build()
            respondToCall(details, response)
        }
        val timeout = Runnable { respond(false) }
        main.postDelayed(timeout, 1200L)
        try {
            worker.execute {
                val blocked = runCatching {
                    val number = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
                    evaluate(true, number, RuleStore(this).read()).decision == Decision.BLOCK_EXPLICIT
                }.getOrDefault(false)
                main.post {
                    main.removeCallbacks(timeout)
                    respond(blocked && SystemClock.elapsedRealtime() < deadline)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            main.removeCallbacks(timeout)
            respond(false)
        }
    }
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
