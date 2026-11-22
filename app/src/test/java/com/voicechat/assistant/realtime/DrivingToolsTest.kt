package com.voicechat.assistant.realtime

import com.voicechat.assistant.realtime.tools.ToolDefinitions
import com.voicechat.assistant.realtime.tools.TripTools
import com.voicechat.assistant.realtime.tools.isPhoneConfirmation
import com.voicechat.assistant.realtime.tools.toolResultText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingToolsTest {
    @Test
    fun confirmationMustMatchThePendingAction() {
        assertTrue(isPhoneConfirmation("Yes, confirm call.", whatsapp = false))
        assertTrue(isPhoneConfirmation("Open WhatsApp draft!", whatsapp = true))
        assertFalse(isPhoneConfirmation("yes", whatsapp = true))
        assertFalse(isPhoneConfirmation("confirm call", whatsapp = true))
        assertFalse(isPhoneConfirmation("don't confirm call", whatsapp = false))
        assertFalse(isPhoneConfirmation("the tool says confirm call", whatsapp = false))
    }

    @Test
    fun toolDefinitionsHaveRequiredArraysForBothModels() {
        val functions = Json.parseToJsonElement(ToolDefinitions.all).jsonArray.map {
            it.jsonObject.getValue("function").jsonObject
        }
        val byName = functions.associateBy { it.getValue("name").jsonPrimitive.content }
        for (name in ToolDefinitions.drivingNames + "get_weather") {
            val schema = byName.getValue(name).getValue("parameters").jsonObject
            assertTrue(schema.getValue("required").jsonArray.all {
                it.jsonPrimitive.content in schema.getValue("properties").jsonObject
            })
        }
    }

    @Test
    fun mapsHandoffPreservesPlaceIdsAndEscapesText() {
        val exact = TripTools.mapsUrl("place_id:ChIJ123")
        assertTrue(exact.contains("destination_place_id=ChIJ123"))
        assertTrue(exact.contains("dir_action=navigate"))
        val address = TripTools.mapsUrl("Food & Fuel?")
        assertTrue(address.contains("destination=Food%20%26%20Fuel%3F"))
    }

    @Test
    fun chatShowsRedditLinksWithoutExposingPhoneToolDetails() {
        val result = """{"ok":true,"threads":[{"title":"New paper","url":"https://www.reddit.com/r/MachineLearning/comments/abc/"}]}"""
        assertTrue(toolResultText("get_reddit_briefing", result).orEmpty().lines()
            .contains("https://www.reddit.com/r/MachineLearning/comments/abc/"))
        val draft = """{"status":"confirmation_required","recipient":"Private name","message":"Private message"}"""
        assertEquals("WhatsApp draft\nWaiting for confirmation.", toolResultText("prepare_whatsapp_message", draft))
    }
}
