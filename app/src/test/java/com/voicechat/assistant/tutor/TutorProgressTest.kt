package com.voicechat.assistant.tutor

import com.voicechat.assistant.core.PromptCatalog
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.SessionPayloads
import com.voicechat.assistant.realtime.tools.ToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TutorProgressTest {
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun `new learners start at A2 and updates merge canonical words`() {
        var progress = TutorProgress()
        assertEquals("A2", progress.level)
        progress = progress.updated(args("""{"vocabulary":[{"topic":"boleto","outcome":"introduced","note":"ticket"}]}"""), 1)
        assertEquals(0, progress.vocabulary.single().attempts)
        progress = progress.updated(args("""{"vocabulary":[{"topic":"Boleto","outcome":"needs_work","note":"Use un boleto"}]}"""), 2)
        progress = progress.updated(args("""{"vocabulary":[{"topic":"boleto","outcome":"correct"},{"topic":"camión","outcome":"introduced"}]}"""), 3)
        assertEquals(2, progress.vocabulary.size)
        val boleto = progress.vocabulary.first { it.topic == "boleto" }
        assertEquals(2, boleto.attempts)
        assertEquals(1, boleto.successes)
        assertEquals("Use un boleto", boleto.note)
        assertTrue(boleto.needsWork)
        assertEquals(2, progress.assessedTurns)
    }

    @Test fun `level changes require observed practice evidence and one CEFR step`() {
        assertEquals("A2", TutorProgress().updated(args("""{"level":"B1","level_evidence":"one answer"}"""), 1).level)
        val experienced = TutorProgress(assessedTurns = 6)
        assertEquals("A2", experienced.updated(args("""{"level":"B1"}"""), 1).level)
        assertEquals("A2", experienced.updated(args("""{"level":"C2","level_evidence":"fluent"}"""), 1).level)
        val advanced = experienced.updated(args("""{"level":"B1","level_evidence":"Past narration accurate in six separate turns"}"""), 1)
        assertEquals("B1", advanced.level)
        assertTrue(advanced.levelEvidence.isNotBlank())
    }

    @Test fun `dialog is deduplicated bounded and redacted`() {
        var progress = TutorProgress()
        repeat(30) { progress = progress.withTurn(TutorTurn("$it", "user", "Practice $it")) }
        assertEquals(24, progress.recentDialog.size)
        progress = progress.withTurn(TutorTurn("29", "user", "Duplicate"))
        assertEquals("Practice 29", progress.recentDialog.last().text)
        progress = progress.withTurn(TutorTurn("new", "user", "my password is verysecret"))
        assertFalse(progress.context().contains("verysecret"))
    }

    @Test fun `older words survive partial updates and recent exercises are bounded`() {
        var progress = TutorProgress()
        repeat(70) { index ->
            progress = progress.updated(args("""{"activity":"scene $index", "grammar":[{"topic":"point $index","outcome":"needs_work"}]}"""), index.toLong())
        }
        assertEquals(70, progress.grammar.size)
        assertEquals(60, progress.activities.size)
        assertTrue(progress.context().contains("scene 69"))
        assertFalse(progress.context().contains("scene 0\n"))
    }

    @Test fun `only explicit full utterances switch modes`() {
        assertEquals(ConversationMode.SPANISH_TUTOR, ConversationMode.command(" Switch to Spanish tutor! "))
        assertEquals(ConversationMode.ASSISTANT, ConversationMode.command("back to voice chat"))
        assertEquals(ConversationMode.ASSISTANT, ConversationMode.command("volver al asistente"))
        assertNull(ConversationMode.command("Translate switch to Spanish tutor into Spanish"))
        assertNull(ConversationMode.command("\"switch to voice chat\""))
        assertNull(ConversationMode.command("Let's talk about a Spanish tutor"))
    }

    @Test fun `tutor tools are isolated and every object schema declares required`() {
        val assistant = ToolDefinitions.json(includeWebSearch = true)
        assertTrue(assistant.contains("switch_conversation_mode"))
        assertFalse(assistant.contains("record_tutor_progress"))
        val tutor = ToolDefinitions.json(includeWebSearch = true, includeTutor = true)
        assertTrue(tutor.contains("record_tutor_progress"))
        assertTrue(tutor.contains("recall_tutor_progress"))
        fun check(value: kotlinx.serialization.json.JsonElement) {
            when (value) {
                is JsonObject -> {
                    if (value["type"]?.jsonPrimitive?.content == "object") assertTrue("Missing required: $value", value["required"] is JsonArray)
                    value.values.forEach(::check)
                }
                is JsonArray -> value.forEach(::check)
                else -> Unit
            }
        }
        check(Json.parseToJsonElement(tutor))
    }

    @Test fun `shipped tutor prompt and session preserve learner delivery and modality`() {
        val catalog = PromptCatalog.parse(File("src/main/assets/prompts.json").readText())!!
        val prompt = catalog.spanishTutor
        assertTrue(prompt.contains("Mexican Spanish"))
        assertTrue(prompt.contains("A2"))
        assertTrue(prompt.contains("very basic"))
        assertTrue(prompt.contains("Use both Spanish and English in text and voice lessons"))
        assertTrue(prompt.contains("Lesson questions in Spanish should adapt"))
        assertTrue(catalog.spanishTutorMemory.contains("Assess ONLY"))
        assertTrue(prompt.contains("English-to-Spanish AND"))
        val config = SessionConfig("model", "voice", prompt, TransportKind.WEBSOCKET, "", "host", "", spanishTutor = true)
        val instructions = Json.parseToJsonElement(SessionPayloads.sessionUpdate(config)).jsonObject["session"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertFalse(instructions.contains("brisk conversational pace"))
        assertTrue(instructions.contains("learner-friendly pace"))
        val response = Json.parseToJsonElement(SessionPayloads.textTurn("Hola").last()).jsonObject["response"]!!.jsonObject
        assertEquals("[\"text\"]", response["modalities"].toString())
    }

    @Test fun `background recap accepts fenced JSON and rejects unrelated output`() {
        assertEquals("ser versus estar", TutorLearning.parse("```json\n{\"summary\":\"ser versus estar\"}\n```")["summary"]!!.jsonPrimitive.content)
        assertTrue(runCatching { TutorLearning.parse("Here is your lesson") }.isFailure)
        assertTrue(runCatching { TutorLearning.parse("{\"ok\":true}") }.isFailure)
    }
}
