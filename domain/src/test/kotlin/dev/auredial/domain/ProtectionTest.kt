package dev.auredial.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtectionTest {
    private val n = "+79991234567"
    @Test fun disabledAllows() = assertEquals(Reason.DISABLED, evaluate(true,n,Rules(blocked=setOf(n))).reason)
    @Test fun outgoingAllows() = assertEquals(Reason.OUTGOING, evaluate(false,n,Rules(true,blocked=setOf(n))).reason)
    @Test fun explicitBlock() = assertEquals(Decision.BLOCK_EXPLICIT, evaluate(true,"+7 (999) 123-45-67",Rules(true,blocked=setOf(n))).decision)
    @Test fun allowWins() = assertEquals(Reason.ALLOW_LIST, evaluate(true,n,Rules(true,setOf(n),setOf(n))).reason)
    @Test fun missingEvidence() = assertEquals(Reason.NO_DATA, evaluate(true,n,Rules(true)).reason)
    @Test fun restricted() = assertEquals(Reason.UNKNOWN, evaluate(true,null,Rules(true)).reason)
    @Test fun invalidNumbers() { listOf("112", "911", "89991234567", "+123abc45678", "+123456789;123", "+٠١٢٣٤٥٦٧٨٩").forEach { assertNull(internationalNumber(it)) } }

    @Test
    fun failedVerificationSilencesAnUnsavedNumberOnlyWithOptIn() {
        val enabled = Rules(enabled = true, silenceFailedVerification = true)
        val unsaved = { ContactMatch.NOT_SAVED }
        assertEquals(Verdict(Decision.SILENCE, Reason.FAILED_VERIFICATION), evaluate(true, n, enabled, true, unsaved))
        assertEquals(Decision.ALLOW, evaluate(true, n, enabled, false, unsaved).decision)
        assertEquals(Decision.ALLOW, evaluate(true, n, enabled.copy(silenceFailedVerification = false), true, unsaved).decision)
        assertEquals(Decision.ALLOW, evaluate(true, n, enabled, true) { ContactMatch.SAVED }.decision)
        assertEquals(Decision.ALLOW, evaluate(true, n, enabled, true) { ContactMatch.UNKNOWN }.decision)
    }

    @Test
    fun allowAndBlockRulesTakePriorityWithoutReadingContacts() {
        val enabled = Rules(enabled = true, silenceFailedVerification = true)
        val blocked = enabled.withNumber(n, allowed = false)
        val allowed = blocked.withNumber(n, allowed = true)
        assertEquals(Decision.BLOCK_EXPLICIT, evaluate(true, n, blocked, true) { error("Unexpected lookup") }.decision)
        assertEquals(Reason.ALLOW_LIST, evaluate(true, n, allowed, true) { error("Unexpected lookup") }.reason)
        assertEquals(emptySet(), allowed.blocked)
        assertEquals(Decision.ALLOW, evaluate(true, "112", blocked, true) { error("Unexpected lookup") }.decision)
    }

    @Test
    fun nationalConversionRunsForCompletePlainNumbersOnly() {
        assertEquals(n, ruleNumber("+7 (999) 123-45-67") { error("Unexpected region lookup") })
        assertEquals(n, ruleNumber("8 (999) 123-45-67") { n })
        assertNull(ruleNumber("8 (999) 123-45-67") { null })
        listOf("112", "*1234567#", "8001234567,22", "+", "123ABC4567", "1234567;1").forEach {
            assertNull(ruleNumber(it) { error("Unexpected region lookup") })
        }
    }
}
