package dev.auredial.app

import android.content.Context
import dev.auredial.domain.Rules

class RuleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun read(): Rules = synchronized(lock) {
        val values = prefs.all
        fun numbers(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        Rules(
            enabled = values["enabled"] == true,
            allowed = numbers("allowed"),
            blocked = numbers("blocked"),
            silenceFailedVerification = values["silence_failed_verification"] == true,
        )
    }

    /** Serialize all edits against the latest snapshot, including edits from call history. */
    fun update(transform: (Rules) -> Rules): Rules? = synchronized(lock) {
        val before = read()
        val after = transform(before)
        if (after == before || write(after)) return@synchronized after

        // SharedPreferences also changes its in-memory copy when commit fails.
        write(before)
        null
    }

    @android.annotation.SuppressLint("UseKtx")
    private fun write(rules: Rules): Boolean = prefs.edit()
        .putBoolean("enabled", rules.enabled)
        .putStringSet("allowed", rules.allowed.toSet())
        .putStringSet("blocked", rules.blocked.toSet())
        .putBoolean("silence_failed_verification", rules.silenceFailedVerification)
        .commit()

    private companion object {
        val lock = Any()
    }
}
