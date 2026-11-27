package dev.auredial.domain

private const val IDENTITY_GRACE_MS = 350L
private const val ANNOUNCEMENT_WINDOW_MS = 9_000L

enum class AnnouncementAction { STOP, WAIT, SPEAK, KEEP }

/** On the main thread, one chance to speak per ringing call. Times are monotonic. */
class AnnouncementSession {
    private var callId: String? = null
    private var ringingSince = 0L
    private var suppressed = false
    private var spoke = false

    fun suppress(id: String?) {
        if (id == callId) suppressed = true
    }

    fun update(
        ringingId: String?,
        eligible: Boolean,
        ready: Boolean,
        now: Long,
    ): AnnouncementAction {
        if (ringingId != callId) {
            callId = ringingId
            ringingSince = now
            suppressed = false
            spoke = false
        }
        if (!eligible) suppressed = true
        if (ringingId == null || suppressed) return AnnouncementAction.STOP
        if (spoke) return AnnouncementAction.KEEP
        val age = now - ringingSince
        if (age >= ANNOUNCEMENT_WINDOW_MS) return AnnouncementAction.STOP
        if (!ready || age < IDENTITY_GRACE_MS) return AnnouncementAction.WAIT
        spoke = true
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
