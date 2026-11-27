package dev.auredial.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle

data class PhoneRow(val key: String, val name: String?, val number: String, val favorite: Boolean=false, val detail: String="")
internal fun Context.hasPermission(permission:String)=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
internal fun Context.isDialer()=getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER)==true
internal fun readPeople(context:Context,history:Boolean):List<PhoneRow> {
    if(history) {
        if(!context.isDialer() || context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        return context.contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls._ID,CallLog.Calls.CACHED_NAME,CallLog.Calls.NUMBER,CallLog.Calls.DATE,CallLog.Calls.TYPE,CallLog.Calls.NUMBER_PRESENTATION),null,null,CallLog.Calls.DATE+" DESC")?.use { cursor ->
            buildList {
                while(size<250 && cursor.moveToNext()) {
                    val number=cursor.getString(2)?.takeIf { cursor.getInt(5)==CallLog.Calls.PRESENTATION_ALLOWED && it.isNotBlank() } ?: continue
                    val type=when(cursor.getInt(4)) {
                        CallLog.Calls.INCOMING_TYPE -> R.string.incoming_call
                        CallLog.Calls.OUTGOING_TYPE -> R.string.outgoing_call
                        CallLog.Calls.MISSED_TYPE -> R.string.missed_call
                        CallLog.Calls.REJECTED_TYPE -> R.string.reject
                        CallLog.Calls.BLOCKED_TYPE -> R.string.block
                        else -> R.string.ongoing_call
                    }
                    val date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(cursor.getLong(3)))
                    add(PhoneRow("log-"+cursor.getLong(0),cursor.getString(1),number,detail=context.getString(type)+" · "+date))
                }
            }
        }.orEmpty()
    }
    if(context.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) return emptyList()
    return context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone._ID,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.Contacts.STARRED),
        null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" COLLATE LOCALIZED ASC")?.use { cursor ->
        buildList {
            while(size<5000 && cursor.moveToNext()) {
                val number=cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                add(PhoneRow("contact-"+cursor.getLong(0),cursor.getString(1),number,cursor.getInt(3)!=0))
            }
        }
    }.orEmpty()
}
internal fun accounts(context:Context):List<Pair<PhoneAccountHandle,String>> = runCatching {
    if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) return emptyList()
    val telecom=context.getSystemService(TelecomManager::class.java)
    telecom.callCapablePhoneAccounts.mapIndexed { i,handle -> handle to (telecom.getPhoneAccount(handle)?.label?.toString() ?: context.getString(R.string.sim_number,i+1)) }
}.getOrDefault(emptyList())
