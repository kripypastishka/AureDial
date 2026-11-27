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
}
