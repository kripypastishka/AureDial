package dev.auredial.domain

private val internationalSyntax = Regex("\\+[1-9][0-9]{7,14}")
private val nationalSyntax = Regex("[0-9 ()-]{7,64}")

/** Strict international syntax, independent of the numbering-plan data on the device. */
fun internationalNumber(raw: String?): String? {
    if (raw == null || raw.length > 64) return null
    val compact = raw.filterNot { it in " -()" }
    return compact.takeIf(internationalSyntax::matches)
}

/** The platform can supply a national conversion only when the region is unambiguous. */
fun ruleNumber(raw: String?, formatNational: (String) -> String?): String? {
    internationalNumber(raw)?.let { return it }
    if (raw == null || !nationalSyntax.matches(raw) || raw.count { it in '0'..'9' } !in 7..15) return null
    return internationalNumber(formatNational(raw))
}

enum class Decision { ALLOW, BLOCK_EXPLICIT, SILENCE }
enum class Reason { DISABLED, OUTGOING, UNKNOWN, ALLOW_LIST, BLOCK_LIST, FAILED_VERIFICATION, NO_DATA }
enum class ContactMatch { SAVED, NOT_SAVED, UNKNOWN }

data class Verdict(val decision: Decision, val reason: Reason)
data class Rules(
    val enabled: Boolean = false,
    val allowed: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
    val silenceFailedVerification: Boolean = false,
) {
    fun withNumber(number: String, allowed: Boolean): Rules = if (allowed) {
        copy(allowed = this.allowed + number, blocked = blocked - number)
    } else {
        copy(allowed = this.allowed - number, blocked = blocked + number)
    }
}

fun evaluate(
    incoming: Boolean,
    rawNumber: String?,
    rules: Rules,
    failedVerification: Boolean = false,
    contactMatch: () -> ContactMatch = { ContactMatch.UNKNOWN },
): Verdict {
    if (!incoming) return Verdict(Decision.ALLOW, Reason.OUTGOING)
    if (!rules.enabled) return Verdict(Decision.ALLOW, Reason.DISABLED)
    val number = internationalNumber(rawNumber) ?: return Verdict(Decision.ALLOW, Reason.UNKNOWN)
    return when {
        number in rules.allowed -> Verdict(Decision.ALLOW, Reason.ALLOW_LIST)
        number in rules.blocked -> Verdict(Decision.BLOCK_EXPLICIT, Reason.BLOCK_LIST)
        rules.silenceFailedVerification && failedVerification && contactMatch() == ContactMatch.NOT_SAVED ->
            Verdict(Decision.SILENCE, Reason.FAILED_VERIFICATION)
        else -> Verdict(Decision.ALLOW, Reason.NO_DATA)
    }
}
