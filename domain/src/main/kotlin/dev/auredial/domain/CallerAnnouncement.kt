package dev.auredial.domain

private const val IDENTITY_GRACE_MS = 350L
private const val ANNOUNCEMENT_WINDOW_MS = 9_000L
private const val MAX_REPEAT_WINDOW_MS = 24_000L

enum class AnnouncementAction { STOP, WAIT, SPEAK, KEEP }

data class AnnouncementTiming(
    val delayMs: Long = IDENTITY_GRACE_MS,
    val count: Int = 1,
    val intervalMs: Long = 3_500L,
)

/** Bounded scheduling for one ringing call. Times are monotonic. */
class AnnouncementSession {
    private var callId: String? = null
    private var ringingSince = 0L
    private var suppressed = false
    private var announcements = 0
    private var lastAnnouncementAt = 0L

    fun suppress(id: String?) {
        if (id == callId) suppressed = true
    }

    fun update(
        ringingId: String?,
        eligible: Boolean,
        ready: Boolean,
        now: Long,
        timing: AnnouncementTiming = AnnouncementTiming(),
    ): AnnouncementAction {
        if (ringingId != callId) {
            callId = ringingId
            ringingSince = now
            suppressed = false
            announcements = 0
            lastAnnouncementAt = 0
        }
        if (!eligible) suppressed = true
        if (ringingId == null || suppressed) return AnnouncementAction.STOP
        if (announcements >= timing.count) return AnnouncementAction.KEEP

        val age = now - ringingSince
        if (announcements == 0 && age >= ANNOUNCEMENT_WINDOW_MS) return AnnouncementAction.STOP
        if (announcements > 0 && age >= MAX_REPEAT_WINDOW_MS) return AnnouncementAction.KEEP
        val due = if (announcements == 0) age >= timing.delayMs.coerceAtLeast(IDENTITY_GRACE_MS)
            else now - lastAnnouncementAt >= timing.intervalMs
        if (!ready || !due) return AnnouncementAction.WAIT
        announcements++
        lastAnnouncementAt = now
        return AnnouncementAction.SPEAK
    }
}

fun speakerIdentity(contactName: String?, number: String?, contactsOnly: Boolean): String? {
    val name = contactName?.take(100)?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        ?.trim()?.takeIf(String::isNotEmpty)
    if (name != null || contactsOnly) return name
    val digits = number?.take(64)?.filter { it in '0'..'9' }.orEmpty()
    if (digits.isEmpty()) return null
    return digits.toList().joinToString(" ")
}
