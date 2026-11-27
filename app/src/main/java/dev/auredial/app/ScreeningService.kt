package dev.auredial.app

import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import dev.auredial.domain.Decision
import dev.auredial.domain.evaluate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class ScreeningService : CallScreeningService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return

        val deadline = SystemClock.elapsedRealtime() + RESPONSE_BUDGET_MS
        val query = CancellationSignal()
        var completed = false
        fun respond(decision: Decision) {
            if (completed) return
            completed = true
            val block = decision == Decision.BLOCK_EXPLICIT
            val response = CallResponse.Builder()
                .setDisallowCall(block)
                .setRejectCall(block)
                .setSilenceCall(decision == Decision.SILENCE)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            respondToCall(details, response)
        }
        // Respond before canceling the provider query. Cancellation can touch Binder.
        val timeout = Runnable {
            respond(Decision.ALLOW)
            runCatching { query.cancel() }
        }
        main.postDelayed(timeout, RESPONSE_BUDGET_MS)
        try {
            worker.execute {
                val decision = runCatching {
                    val rules = RuleStore(this).read()
                    val raw = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
                    val number = if (rules.enabled) normalizeRuleNumber(this, raw) else null
                    evaluate(true, number, rules, details.failedVerification()) {
                        contactMatch(this, checkNotNull(raw), query)
                    }.decision
                }.getOrDefault(Decision.ALLOW)
                main.post {
                    main.removeCallbacks(timeout)
                    respond(if (SystemClock.elapsedRealtime() < deadline) decision else Decision.ALLOW)
                }
            }
        } catch (_: RejectedExecutionException) {
            main.removeCallbacks(timeout)
            respond(Decision.ALLOW)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val RESPONSE_BUDGET_MS = 1_200L
    }
}

internal fun Call.Details.failedVerification(): Boolean =
    Build.VERSION.SDK_INT >= 30 && callDirection == Call.Details.DIRECTION_INCOMING &&
        callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
