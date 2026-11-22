package com.voicechat.assistant.realtime.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal class DeviceActions(private val context: Context) {
    data class Pending(
        val name: String,
        val number: String,
        val message: String?,
        val expiresAt: Long = SystemClock.elapsedRealtime() + 45_000,
    ) {
        fun prompt(): String = buildJsonObject {
            put("ok", true)
            put("status", "confirmation_required")
            put("recipient", name)
            message?.let { put("message", it) }
            put("next", if (message == null) "Ask: Call $name? Say 'confirm call'." else
                "Read back the exact message and recipient. Ask the user to say 'open WhatsApp draft' when parked.")
        }.toString()
    }

    data class Resolved(val pending: Pending? = null, val error: String = "")

    fun resolve(target: String, message: String? = null): Resolved {
        if (target.isBlank()) return Resolved(error = "A contact name or phone number is required.")
        if (message != null && (message.isBlank() || message.length > 2000)) {
            return Resolved(error = "Provide a message between 1 and 2000 characters.")
        }
        val direct = target.matches(Regex("[+0-9 ().-]+"))
        val match = if (direct) target to PhoneNumberUtils.normalizeNumber(target) else {
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                return Resolved(error = "Enable Contacts access in VoiceChat Settings while parked, or provide a number.")
            }
            val choice = lookup(target)
            if (choice.isEmpty()) return Resolved(error = "No matching contact number for $target.")
            if (choice.size != 1) return Resolved(error = "Several numbers match $target. Ask for the full name, mobile, home, or work number.")
            choice.single()
        }
        val number = if (message == null) match.second else PhoneNumberUtils.formatNumberToE164(
            match.second,
            Locale.getDefault().country,
        ) ?: return Resolved(error = "Provide the WhatsApp number with its international country code.")
        if (!number.matches(Regex("\\+?[0-9]{3,15}"))) return Resolved(error = "That phone number is invalid.")
        return Resolved(Pending(match.first, number, message))
    }

    private fun lookup(target: String): List<Pair<String, String>> {
        val label = target.substringAfterLast(' ').lowercase(Locale.ROOT)
        val type = when (label) {
            "mobile", "cell" -> Phone.TYPE_MOBILE
            "home" -> Phone.TYPE_HOME
            "work" -> Phone.TYPE_WORK
            else -> null
        }
        val name = if (type == null) target else target.substringBeforeLast(' ')
        fun find(query: String, numberType: Int?): List<Pair<String, String>> {
            val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE)
            val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query))
            return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(0).orEmpty()
                        if (!displayName.equals(query, true) &&
                            !displayName.split(' ').any { it.equals(query, true) }) continue
                        if (numberType != null && cursor.getInt(2) != numberType) continue
                        val number = PhoneNumberUtils.normalizeNumber(cursor.getString(1).orEmpty())
                        if (number.isNotBlank()) add(displayName to number)
                    }
                }
            }.orEmpty().distinctBy { it.second }
        }
        return find(target, null).ifEmpty { if (type != null) find(name, type) else emptyList() }
    }

    @SuppressLint("MissingPermission")
    suspend fun complete(action: Pending): String {
        if (SystemClock.elapsedRealtime() > action.expiresAt) return toolError("The confirmation expired.")
        val message = action.message
        if (message == null) {
            val allowed = hasPermission(Manifest.permission.CALL_PHONE)
            val intent = Intent(if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.fromParts("tel", action.number, null))
            if (!launch(intent)) return toolError("Android couldn't open the phone app.")
            return status(if (allowed) "call_requested" else "dialer_opened",
                if (allowed) "Handed the call to Android." else "Enable Phone access in VoiceChat Settings for hands-free calls.")
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) return toolError("Unlock the phone when parked to open the WhatsApp draft.")
        val uri = Uri.Builder().scheme("https").authority("wa.me")
            .appendPath(action.number.trimStart('+')).appendQueryParameter("text", message).build()
        if (!launch(Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP))) {
            return toolError("WhatsApp couldn't open the draft. Make sure the personal WhatsApp app is installed.")
        }
        return status("draft_opened", "WhatsApp opened a draft. Tap Send when parked. No message has been sent.")
    }

    suspend fun navigate(destination: String): String {
        if (destination.isBlank()) return toolError("A destination is required.")
        val url = TripTools.mapsUrl(destination)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps")
        if (!launch(intent)) return toolError("Google Maps couldn't open the driving directions.")
        return buildJsonObject {
            put("ok", true)
            put("status", "maps_opened")
            put("url", url)
        }.toString()
    }

    suspend fun pocketCasts(args: JsonObject): String {
        val action = args.string("action")
        val query = args.string("query").take(200)
        if (action == "open") {
            val intent = context.packageManager.getLaunchIntentForPackage(POCKET_CASTS)
            if (intent == null || !launch(intent)) return toolError("Pocket Casts couldn't open.")
            return status("app_opened", "Opened Pocket Casts.")
        }
        if (action == "open_episode") {
            val url = PocketCastsCatalog.episodeLink(args.string("url"))
                ?: return toolError("Look up a Pocket Casts episode first.")
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).setPackage(POCKET_CASTS))) {
                return toolError("This version of Pocket Casts couldn't open the episode link. Use the link in the chat.")
            }
            return status("episode_opened", "Opened the episode link. Pocket Casts controls playback.")
        }
        if (action == "play_search") {
            if (query.isBlank()) return toolError("Which podcast or episode?")
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(POCKET_CASTS)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(android.app.SearchManager.QUERY, query)
            if (launch(intent)) return status("playback_requested", "Asked Pocket Casts to play a match for $query.")
            return toolError("This version of Pocket Casts could not open a playback search.")
        }
        val command = when (action) {
            "resume" -> "PLAY"
            "pause" -> "PAUSE"
            "next" -> "NEXT"
            "skip_forward" -> "SKIP_FORWARD"
            "skip_backward" -> "SKIP_BACKWARD"
            else -> return toolError("Unknown Pocket Casts action.")
        }
        val intent = Intent("$POCKET_CASTS.action.$command").setPackage(POCKET_CASTS)
        if (command.startsWith("SKIP") && args.string("seconds").isNotBlank()) {
            val seconds = args.string("seconds").toIntOrNull()?.takeIf { it in 1..600 }
                ?: return toolError("Choose a skip duration of 1–600 seconds.")
            intent.putExtra("$POCKET_CASTS.extra.SECONDS", seconds)
        }
        if (context.packageManager.queryBroadcastReceivers(intent, 0).isEmpty()) {
            return toolError("Pocket Casts isn't installed or doesn't support this playback command.")
        }
        context.sendBroadcast(intent)
        return status("playback_requested", "Sent the $action command to Pocket Casts.")
    }

    private suspend fun launch(intent: Intent): Boolean = withContext(Dispatchers.Main.immediate) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun status(value: String, detail: String): String = buildJsonObject {
        put("ok", true)
        put("status", value)
        put("detail", detail)
    }.toString()

    companion object {
        private const val POCKET_CASTS = "au.com.shiftyjelly.pocketcasts"
        private const val WHATSAPP = "com.whatsapp"
    }
}
