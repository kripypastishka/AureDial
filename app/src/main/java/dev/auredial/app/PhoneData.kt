package dev.auredial.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dev.auredial.domain.ContactMatch
import dev.auredial.domain.ruleNumber
import java.util.Locale

data class PhoneRow(
    val key: String,
    val name: String?,
    val number: String,
    val favorite: Boolean = false,
    val detail: String = "",
    val contactUri: Uri? = null,
    val callType: Int? = null,
)

internal fun Context.hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
internal fun Context.isDialer() = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true

internal fun readPeople(context: Context, history: Boolean): List<PhoneRow> =
    if (history) readHistory(context) else readContacts(context)

private fun readHistory(context: Context): List<PhoneRow> {
    if (!context.isDialer() || !context.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()
    val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
        CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.NUMBER_PRESENTATION)
    val formatter = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)

    return context.contentResolver.query(
        CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC",
    )?.use { cursor ->
        buildList {
            while (size < 250 && cursor.moveToNext()) {
                val visible = cursor.getInt(5) == CallLog.Calls.PRESENTATION_ALLOWED
                val number = if (visible) cursor.getString(2).orEmpty() else ""
                val name = if (visible) cursor.getString(1)?.takeIf(String::isNotBlank) else null
                val callType = cursor.getInt(4)
                val label = when (callType) {
                    CallLog.Calls.INCOMING_TYPE -> R.string.incoming_call
                    CallLog.Calls.OUTGOING_TYPE -> R.string.outgoing_call
                    CallLog.Calls.MISSED_TYPE -> R.string.missed_call
                    CallLog.Calls.REJECTED_TYPE -> R.string.rejected_call
                    CallLog.Calls.BLOCKED_TYPE -> R.string.blocked_call
                    else -> R.string.ongoing_call
                }
                val time = formatter.format(java.util.Date(cursor.getLong(3)))
                add(PhoneRow("log-" + cursor.getLong(0), name, number,
                    detail = context.getString(label) + " · " + time, callType = callType))
            }
        }
    }.orEmpty()
}

private fun readContacts(context: Context): List<PhoneRow> {
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
    val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.Contacts.STARRED,
        ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
    return context.contentResolver.query(
        phone, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
    )?.use { cursor ->
        buildList {
            while (size < 5000 && cursor.moveToNext()) {
                val number = cursor.getString(2)?.takeIf(String::isNotBlank) ?: continue
                val contactUri = ContactsContract.Contacts.getLookupUri(cursor.getLong(5), cursor.getString(4))
                add(PhoneRow("contact-" + cursor.getLong(0), cursor.getString(1)?.takeIf(String::isNotBlank),
                    number, cursor.getInt(3) != 0, contactUri = contactUri))
            }
        }
    }.orEmpty()
}

internal fun accounts(context:Context):List<Pair<PhoneAccountHandle,String>> = runCatching {
    if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) return emptyList()
    val telecom=context.getSystemService(TelecomManager::class.java)
    telecom.callCapablePhoneAccounts.mapIndexed { i,handle -> handle to (telecom.getPhoneAccount(handle)?.label?.toString() ?: context.getString(R.string.sim_number,i+1)) }
}.getOrDefault(emptyList())

internal fun normalizeRuleNumber(context: Context, raw: String?): String? =
    ruleNumber(raw) { national ->
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return@ruleNumber null
        // Screening does not receive a SIM identifier. Require a single active
        // modem and agreement between network and SIM country to interpret local digits.
        val modemCount = if (Build.VERSION.SDK_INT >= 30) telephony.activeModemCount
            else @Suppress("DEPRECATION") telephony.phoneCount
        if (modemCount != 1) return@ruleNumber null
        val region = telephony.simCountryIso.takeIf { it.length == 2 && it.equals(telephony.networkCountryIso, true) }
            ?: return@ruleNumber null
        PhoneNumberUtils.formatNumberToE164(national, region.uppercase(Locale.ROOT))
    }

internal fun contactMatch(
    context: Context,
    number: String,
    cancellationSignal: CancellationSignal,
): ContactMatch {
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return ContactMatch.UNKNOWN
    val lookup = contactLookupUri(number)
    return context.contentResolver.query(
        lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null, cancellationSignal,
    )?.use {
        if (it.moveToFirst()) ContactMatch.SAVED else ContactMatch.NOT_SAVED
    } ?: ContactMatch.UNKNOWN
}

internal fun lookupContactName(context: Context, number: String): String? {
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return null
    val lookup = contactLookupUri(number)
    return context.contentResolver.query(
        lookup, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
    )?.use { if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null }
}

private fun contactLookupUri(number: String): Uri =
    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
