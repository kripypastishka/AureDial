package dev.auredial.domain

/** Strict international syntax, not a claim that a number exists. No country guessing. */
fun internationalNumber(raw: String?): String? {
    if (raw == null || raw.length > 64) return null
    val compact = raw.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
    return compact.takeIf { it.matches(Regex("\\+[1-9][0-9]{7,14}")) }
}

enum class Decision { ALLOW, BLOCK_EXPLICIT }
enum class Reason { DISABLED, OUTGOING, UNKNOWN, ALLOW_LIST, BLOCK_LIST, NO_DATA }
data class Verdict(val decision: Decision, val reason: Reason)
data class Rules(val enabled: Boolean = false, val allowed: Set<String> = emptySet(), val blocked: Set<String> = emptySet())

/** No statistical or reputation claims without evidence. Explicit allow wins. */
fun evaluate(incoming: Boolean, rawNumber: String?, rules: Rules): Verdict {
    if (!incoming) return Verdict(Decision.ALLOW, Reason.OUTGOING)
    if (!rules.enabled) return Verdict(Decision.ALLOW, Reason.DISABLED)
    val number = internationalNumber(rawNumber) ?: return Verdict(Decision.ALLOW, Reason.UNKNOWN)
    return when (number) {
        in rules.allowed -> Verdict(Decision.ALLOW, Reason.ALLOW_LIST)
        in rules.blocked -> Verdict(Decision.BLOCK_EXPLICIT, Reason.BLOCK_LIST)
        else -> Verdict(Decision.ALLOW, Reason.NO_DATA)
    }
}
