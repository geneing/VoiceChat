package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Details that are useful to revisit after a short spoken answer. */
internal fun toolResultText(name: String, result: String): String? {
    val root = runCatching { Json.parseToJsonElement(result) as JsonObject }.getOrNull() ?: return null
    val title = when (name) {
        "get_weather" -> "Weather forecast"
        "get_reddit_briefing" -> "Reddit discussions"
        "find_nearby" -> "Nearby places"
        "get_trip_eta" -> "Trip estimate"
        "start_navigation" -> "Google Maps"
        "play_pocket_casts" -> "Pocket Casts"
        "save_for_later" -> "Saved for later"
        "call_phone", "confirm_phone_action" -> "Phone action"
        "prepare_whatsapp_message" -> "WhatsApp draft"
        else -> return null
    }
    return buildString {
        appendLine(title)
        when (name) {
            "call_phone", "confirm_phone_action", "prepare_whatsapp_message" -> {
                append(root.string("detail").ifBlank {
                    if (root.string("status") == "confirmation_required") "Waiting for confirmation."
                    else "Phone action unavailable."
                })
            }
            "get_weather" -> {
                root.string("place").takeIf(String::isNotBlank)?.let { appendLine(it) }
                root.array("daily").forEach { day ->
                    appendLine(listOf(
                        day.string("date"), day.string("condition"),
                        day.string("low_c").takeIf(String::isNotBlank)?.let { "$it°C low" }.orEmpty(),
                        day.string("high_c").takeIf(String::isNotBlank)?.let { "$it°C high" }.orEmpty(),
                        day.string("rain_chance_pct").takeIf(String::isNotBlank)?.let { "$it% rain" }.orEmpty(),
                    ).filter(String::isNotBlank).joinToString(" · "))
                }
            }
            "get_reddit_briefing", "find_nearby", "save_for_later", "play_pocket_casts" -> {
                val rows = when (name) {
                    "get_reddit_briefing" -> root.array("threads")
                    "find_nearby" -> root.array("places")
                    "play_pocket_casts" -> root.array("matches")
                    else -> root.obj("saved")?.let { listOf(it) } ?: root.array("saved")
                }
                rows.forEach { row ->
                    val label = row.string("title").ifBlank { row.string("name") }
                    val date = row.string("posted_at").take(10)
                    appendLine(listOf(label, row.string("community"), date, row.string("address"))
                        .filter(String::isNotBlank).joinToString(" · "))
                    if (name == "save_for_later") {
                        row.string("note").takeIf(String::isNotBlank)?.let { appendLine(it) }
                    }
                    row.string("url").takeIf(String::isNotBlank)?.let { appendLine(it) }
                }
                if (rows.isEmpty() && root.string("ok") == "true") {
                    appendLine(root.string("detail").ifBlank { "No results." })
                }
                root.string("source").takeIf(String::isNotBlank)?.let { appendLine(it) }
                root.string("source_url").takeIf(String::isNotBlank)?.let { appendLine(it) }
            }
            "get_trip_eta" -> {
                root.string("estimated_minutes").takeIf(String::isNotBlank)?.let { appendLine("$it min") }
                if (root.string("traffic_aware") == "false") appendLine("Estimate without live traffic")
                root.string("source").takeIf(String::isNotBlank)?.let { appendLine(it) }
                root.string("source_url").takeIf(String::isNotBlank)?.let { appendLine(it) }
                root.string("fix_map_url").takeIf(String::isNotBlank)?.let { appendLine(it) }
            }
        }
        if (name !in setOf("call_phone", "confirm_phone_action", "prepare_whatsapp_message")) {
            root.string("error").takeIf(String::isNotBlank)?.let { appendLine(it) }
            root.string("coverage").takeIf(String::isNotBlank)?.let { appendLine(it) }
            root.string("no_indexed_results").takeIf(String::isNotBlank)?.let { appendLine("No indexed discussions for $it") }
            root.string("url").takeIf(String::isNotBlank)?.let { appendLine(it) }
        }
    }.trim()
}
