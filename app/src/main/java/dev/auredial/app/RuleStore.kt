package dev.auredial.app

import android.content.Context
import dev.auredial.domain.Rules

/** Small local-only rules for the alpha. Replace with transactional DataStore before release. */
class RuleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)
    fun read(): Rules = prefs.all.let { values ->
        fun numbers(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        Rules(values["enabled"] == true, numbers("allowed"), numbers("blocked"))
    }
    fun write(rules: Rules): Boolean = prefs.edit().putBoolean("enabled",rules.enabled)
        .putStringSet("allowed", rules.allowed.toSet()).putStringSet("blocked",rules.blocked.toSet()).commit()
}
