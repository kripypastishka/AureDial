package dev.auredial.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallerAnnouncementTest {
    @Test
    fun waitsForIdentityAndEngineBeforeSpeakingOnce() {
        val session = AnnouncementSession()
        assertEquals(AnnouncementAction.WAIT, session.update("call", true, true, true, 0))
        assertEquals(AnnouncementAction.WAIT, session.update("call", true, true, false, 400))
        assertEquals(AnnouncementAction.SPEAK, session.update("call", true, true, true, 500))
        assertEquals(AnnouncementAction.KEEP, session.update("call", true, true, true, 700))
        assertEquals(AnnouncementAction.STOP, session.update("call", true, false, true, 900))
        assertEquals(AnnouncementAction.KEEP, session.update("call", true, true, true, 1_000))
    }

    @Test
    fun stopsImmediatelyWhenAnsweredAndAllowsNextCall() {
        val session = AnnouncementSession()
        session.update("first", true, true, true, 0)
        session.update("first", true, true, true, 400)
        session.suppress("first")
        assertEquals(AnnouncementAction.STOP, session.update("first", true, true, true, 401))
        assertEquals(AnnouncementAction.STOP, session.update(null, false, true, true, 402))
        assertEquals(AnnouncementAction.WAIT, session.update("second", true, true, true, 500))
        assertEquals(AnnouncementAction.SPEAK, session.update("second", true, true, true, 900))
    }

    @Test
    fun suppressionPersistsAfterAnotherCallOrSilentRinging() {
        val session = AnnouncementSession()
        assertEquals(AnnouncementAction.STOP, session.update("call", false, true, true, 0))
        assertEquals(AnnouncementAction.STOP, session.update("call", true, true, true, 500))
    }

    @Test
    fun screenOpenedTooLateDoesNotTriggerAnnouncement() {
        val session = AnnouncementSession()
        assertEquals(AnnouncementAction.STOP, session.update("call", true, false, true, 0))
        assertEquals(AnnouncementAction.STOP, session.update("call", true, true, true, 6_000))
    }

    @Test
    fun usesLocalIdentityOrDigitsAndRespectsContactsOnlyMode() {
        assertEquals("Алёна", speakerIdentity("А\u202Eлёна\n", "+7 (999) 12", false))
        assertEquals("7 9 9 9 1 2", speakerIdentity(null, "+7 (999) 12", false))
        assertNull(speakerIdentity(null, "+7 (999) 12", true))
        assertNull(speakerIdentity(null, null, false))
    }
}
