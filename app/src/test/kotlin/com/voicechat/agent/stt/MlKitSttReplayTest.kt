package com.voicechat.agent.stt

import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.replay.FixtureCondition
import com.voicechat.agent.replay.FixtureLabels
import com.voicechat.agent.replay.FixtureVariants
import com.voicechat.agent.replay.PcmFixture
import com.voicechat.agent.replay.ReplayAudioInput
import com.voicechat.agent.replay.ReplayFixtures
import com.voicechat.agent.replay.ReplaySpeechToText
import com.voicechat.agent.replay.TranscriptLabel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M08 acceptance: the [SttEvent] contract path handles partial revisions,
 * finalization, empty input, corrections, numbers, names, negation,
 * disfluency, and low-SNR variants.
 *
 * The real engine needs a device, so these tests reuse the M03 replay
 * infrastructure ([ReplayFixtures], [PcmFixture], [ReplayAudioInput],
 * [ReplaySpeechToText]) rather than duplicating it. They do not pretend the ML
 * Kit engine is available and need no credentials, network, or microphone.
 */
class MlKitSttReplayTest {
    private fun fixtureWith(
        suffix: String,
        labels: FixtureLabels,
    ): PcmFixture {
        val base = ReplayFixtures.syntheticPauseResume()
        return PcmFixture(
            manifest = base.manifest.copy(id = "synthetic/m08-$suffix", labels = labels),
            samples = base.samples,
        )
    }

    private fun labels(
        finalTranscript: String,
        vararg revisions: Pair<String, Boolean>,
    ): FixtureLabels =
        FixtureLabels(
            finalTranscript = finalTranscript,
            transcriptRevisions =
                revisions.mapIndexed { index, (text, isFinal) ->
                    TranscriptLabel(frameIndex = index, text = text, isFinal = isFinal)
                },
            vadEvents = emptyList(),
            turnCompletions = emptyList(),
        )

    private suspend fun replay(fixture: PcmFixture): List<SttEvent> =
        ReplaySpeechToText(fixture)
            .transcribe(ReplayAudioInput(fixture).frames())
            .toList()

    private fun List<SttEvent>.transcripts(): List<Transcript> = map { (it as SttEvent.Result).transcript }

    @Test
    fun revisedPartialsPrecedeTheCorrectionAndFinalization() =
        runTest {
            val fixture =
                fixtureWith(
                    suffix = "corrections",
                    labels =
                        labels(
                            "ice cream",
                            "ice scream" to false,
                            "ice cream" to false,
                            "ice cream" to true,
                        ),
                )

            val events = replay(fixture)
            val transcripts = events.transcripts()

            assertEquals(listOf("ice scream", "ice cream", "ice cream"), transcripts.map { it.text })
            assertEquals(listOf(false, false, true), transcripts.map { it.isFinal })
            assertEquals(listOf(0, 1, 2), transcripts.map { it.revision.value })
        }

    @Test
    fun numbersNamesNegationAndDisfluencySurviveTheContractVerbatim() =
        runTest {
            val cases =
                mapOf(
                    "numbers" to "call me at 5:30 pm",
                    "names" to "schedule with Priya Nair",
                    "negation" to "don't send the report",
                    "disfluency" to "um, I mean, uh, send it",
                )

            cases.forEach { (name, spoken) ->
                val fixture = fixtureWith(suffix = name, labels = labels(spoken, spoken.take(3) to false, spoken to true))

                val final = replay(fixture).transcripts().last()

                assertTrue(final.isFinal)
                assertEquals(spoken, final.text)
            }
        }

    @Test
    fun emptyInputProducesNoSuccessShapedResult() =
        runTest {
            val fixture = fixtureWith(suffix = "empty", labels = labels(""))

            val events = replay(fixture)

            assertTrue("an empty fixture must not claim a result", events.isEmpty())
        }

    @Test
    fun lowSnrVariantsReplayTheSameContractEventsDeterministically() =
        runTest {
            val base =
                fixtureWith(
                    suffix = "base",
                    labels = labels("ice cream", "ice scream" to false, "ice cream" to true),
                )

            listOf(
                FixtureCondition.STREET_NOISE,
                FixtureCondition.CAR_NOISE,
                FixtureCondition.COMPRESSION,
            ).forEach { condition ->
                val variant = FixtureVariants.variant(base, condition)

                val baseEvents = replay(base)
                val variantEvents = replay(variant)

                assertEquals("${condition.name} must not change the labels", baseEvents, variantEvents)
                assertTrue(
                    "${condition.name} must record the degradation it applied",
                    variant.manifest.transformations.isNotEmpty(),
                )
            }
        }

    @Test
    fun theEngineIdComesFromTheFixtureManifest() =
        runTest {
            val fixture =
                fixtureWith(suffix = "engine", labels = labels("hello again", "hello" to false, "hello again" to true))

            val recognizer = ReplaySpeechToText(fixture)

            assertEquals(fixture.manifest.engine.engineId, recognizer.engineId.value)
        }
}
