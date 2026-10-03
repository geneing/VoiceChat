package com.voicechat.agent.eval.litert

import com.voicechat.agent.eval.SpeechQualityScorer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Host-side WER/CER harness for the LiteRT ASR spike.
 *
 * It scores a recognized hypothesis against a reference label using the M25
 * [SpeechQualityScorer]. Both inputs are local, Git-ignored text files so no
 * transcript or audio enters Git and scoring stays fully reproducible.
 *
 * Inputs live under `test_data/litert-asr-wer/`:
 * - `<modelLabel>.hypothesis.txt` — decoded on-device output for one model/clip.
 * - `<clipId>.reference.txt` — a local transcription used as the reference.
 * - `cases.csv` — rows `modelLabel,clipId` binding each hypothesis to a reference.
 *
 * When the directory, index, or a file is absent the test is skipped, so a clean
 * checkout and CI never fail. `-Dvoicechat.wer.hypothesis=<file>
 * -Dvoicechat.wer.reference=<file>` scores one explicit pair instead, and
 * `-Dvoicechat.wer.report=<file>` writes one content-free CSV row per case.
 *
 * A reference produced by a local model is a pseudo-reference: a WER here is
 * agreement with that reference, not gold accuracy, unless it was human-reviewed.
 */
class LiteRtWerHarnessTest {
    @Test
    fun scoresAvailableHypothesesAgainstLocalReferences() {
        val explicitHypothesis = System.getProperty("voicechat.wer.hypothesis")
        val explicitReference = System.getProperty("voicechat.wer.reference")

        val pairs: List<Pair<String, Pair<File, File>>> =
            if (!explicitHypothesis.isNullOrBlank() && !explicitReference.isNullOrBlank()) {
                listOf(
                    "explicit" to Pair(File(explicitHypothesis).absoluteFile, File(explicitReference).absoluteFile),
                ).filter { (_, files) -> files.first.isFile && files.second.isFile }
            } else {
                discoverPairs()
            }

        assumeTrue(
            "No local LiteRT WER pairs found. Provide test_data/litert-asr-wer/cases.csv with matching " +
                "<model>.hypothesis.txt and <clip>.reference.txt, or -Dvoicechat.wer.hypothesis / -Dvoicechat.wer.reference.",
            pairs.isNotEmpty(),
        )

        val referenceSource = System.getProperty("voicechat.wer.reference.source") ?: "local_whisper"
        val windowSeconds = System.getProperty("voicechat.wer.window.seconds")?.toDoubleOrNull()
        val clipDurations = parseDurations(System.getProperty("voicechat.wer.clip.durations").orEmpty())
        val reportLines = mutableListOf("case,referenceSource,referenceWords,substitutions,deletions,insertions,wer,cer,categories")
        var scored = 0
        var skippedLong = 0
        for ((caseId, files) in pairs) {
            val (hypothesisFile, referenceFile) = files
            if (windowSeconds != null) {
                val duration = clipDurations[caseId.substringAfter('@')]
                if (duration != null && duration > windowSeconds) {
                    skippedLong++
                    continue
                }
            }
            val reference = referenceFile.readText().trim()
            val hypothesis = hypothesisFile.readText().trim()
            assumeTrue("Empty reference for $caseId: ${referenceFile.path}", reference.isNotEmpty())

            val result = SpeechQualityScorer.score(reference = reference, hypothesis = hypothesis)
            val categories =
                result.categories
                    .map { it.id }
                    .sorted()
                    .joinToString("|")
            reportLines +=
                listOf(
                    caseId,
                    referenceSource,
                    result.referenceWordCount,
                    result.substitutions,
                    result.deletions,
                    result.insertions,
                    "%.6f".format(result.wer),
                    "%.6f".format(result.cer),
                    categories,
                ).joinToString(",")
            scored++
            assertTrue("WER must be non-negative for $caseId", result.wer >= 0.0)
        }
        if (windowSeconds != null) {
            println("WER window filter: scored=$scored skippedLong=$skippedLong windowSeconds=$windowSeconds")
        }

        System.getProperty("voicechat.wer.report")?.let { reportPath ->
            File(reportPath).writeText(reportLines.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    /**
     * Parses `-Dvoicechat.wer.clip.durations=clip=4.91,clip2=13.0` into a map so
     * the harness can exclude clips longer than the model window from an
     * in-window WER without hardcoding durations in the test.
     */
    private fun parseDurations(value: String): Map<String, Double> =
        value
            .split(',')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) {
                    null
                } else {
                    parts[1].trim().toDoubleOrNull()?.let { parts[0].trim() to it }
                }
            }.toMap()

    private fun discoverPairs(): List<Pair<String, Pair<File, File>>> {
        val configured =
            System.getProperty("voicechat.wer.dir") ?: "test_data/litert-asr-wer"
        val directory = resolveAgainstModuleOrRoot(configured)
        if (!directory.isDirectory) return emptyList()
        val index = File(directory, "cases.csv")
        if (!index.isFile) return emptyList()
        return index
            .readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val columns = line.split(',').map { it.trim() }
                if (columns.size < 2) return@mapNotNull null
                val (modelLabel, clipId) = columns
                val hypothesis = File(directory, "$modelLabel--$clipId.hypothesis.txt")
                val reference = File(directory, "$clipId.reference.txt")
                if (!hypothesis.isFile || !reference.isFile) return@mapNotNull null
                "$modelLabel@$clipId" to Pair(hypothesis, reference)
            }.sortedBy { (caseId, _) -> caseId }
            .toList()
    }

    /**
     * Gradle's unit-test working directory can be the `app/` module, a daemon
     * directory, or the repo root depending on configuration. Prefer the given
     * path if it exists, otherwise walk up from the working directory until a
     * `test_data/` sibling is found.
     */
    private fun resolveAgainstModuleOrRoot(path: String): File {
        val candidate = File(path)
        if (candidate.isDirectory) return candidate
        var current: File? = File("").absoluteFile
        while (current != null) {
            val rooted = File(current, path)
            if (rooted.isDirectory) return rooted
            current = current.parentFile
        }
        return candidate
    }
}
