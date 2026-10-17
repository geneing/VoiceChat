package com.voicechat.agent.tts.kokoro.phonemes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class KokoroPhonemizerPortTest {
    @Test
    fun corpusMatchesMisakiSentenceForSentence() {
        val phonemizer = phonemizer()
        val records = fixtures.getValue("corpus").jsonArray
        var matches = 0
        val differences = mutableMapOf<String, Int>()
        records.forEach { element ->
            val row = element.jsonObject
            val text = row.getValue("text").jsonPrimitive.content
            val actual = phonemizer.phonemes(text)
            val expected = row.getValue("phonemes").jsonPrimitive.content
            if (actual == expected) {
                matches++
            } else {
                val category = category(text)
                differences[category] = differences.getOrDefault(category, 0) + 1
            }
        }
        val rate = matches.toDouble() / records.size
        System.err.println(
            "Misaki sentence parity: $matches/${records.size} (${"%.1f".format(rate * 100)}%); " +
                "differences by category: $differences",
        )
        assertTrue("Sentence-exact parity was ${(rate * 100)}%; differences: $differences", rate >= 0.90)
    }

    @Test
    fun fixturesCoverPhonemesAndEncodedChunkIds() {
        val phonemizer = phonemizer()
        val vocab = vocab()
        (fixtures.getValue("corpus").jsonArray + fixtures.getValue("edgeCases").jsonArray)
            .forEachIndexed { fixtureIndex, element ->
                val row = element.jsonObject
                val text = row.getValue("text").jsonPrimitive.content
                val expected = row.getValue("phonemes").jsonPrimitive.content
                val actual = phonemizer.phonemes(text)
                assertTrue(
                    "phonemes differ for fixture $fixtureIndex: ${summarizeDiff(expected, actual)}",
                    expected == actual,
                )
                val chunks = row.getValue("chunks").jsonArray.map { it.jsonPrimitive.content }
                val titlChunks = KokoroPhonemeChunker().chunks(text, phonemizerChunkLimit(), phonemizer::phonemes)
                assertTrue(
                    "titl chunks differ for fixture $fixtureIndex",
                    chunks == titlChunks.map { it.phonemes },
                )
                val sourceChunks = phonemizer.chunks(text, phonemizerChunkLimit())
                if (text !in punctuationOnly) {
                    assertTrue(
                        "chunks differ for fixture $fixtureIndex",
                        chunks == sourceChunks.map { it.phonemes },
                    )
                }
                row.getValue("ids").jsonArray.forEachIndexed { index, ids ->
                    assertArrayEquals(
                        "encoded ids for fixture $fixtureIndex chunk $index",
                        ids.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray(),
                        vocab.encode(chunks[index]),
                    )
                }
            }
    }

    @Test
    fun chunkSourceTextIsAssignedOnlyToTheFirstPieceOfLongSentence() {
        val text = "The little rabbit ran through the quiet garden and looked at the old door."
        val chunks = phonemizer().chunks(text, maxPhonemes = 12)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.first().sourceText)
        assertTrue(chunks.drop(1).all { it.sourceText.isEmpty() })
    }

    private fun phonemizer(): DefaultKokoroPhonemizer =
        DefaultKokoroPhonemizer.fromFiles(
            File("src/main/assets/kokoro/vocab.json"),
            File("src/main/assets/kokoro/us_gold.json.gz"),
            File("src/main/assets/kokoro/us_silver.json.gz"),
            File("src/main/assets/kokoro/en-pos-perceptron.bin"),
        )

    private fun vocab(): KokoroVocab = KokoroVocab.load(File("src/main/assets/kokoro/vocab.json").inputStream())

    private fun category(text: String): String =
        when {
            text.all { !it.isLetterOrDigit() && !it.isWhitespace() } -> "punctuation"
            text.any(Char::isDigit) -> "numbers"
            text.any { it in "$€£%&+@" } -> "symbols-and-currency"
            text.contains('[') -> "markdown"
            Regex("\\b[A-Z]{2,}\\b").containsMatchIn(text) -> "acronyms"
            else -> "ordinary"
        }

    private fun summarizeDiff(
        expected: String,
        actual: String,
    ): String {
        val index =
            (0 until minOf(expected.length, actual.length)).firstOrNull { expected[it] != actual[it] }
                ?: minOf(expected.length, actual.length)
        val start = (index - 10).coerceAtLeast(0)
        val expectedEnd = (index + 10).coerceAtMost(expected.length)
        val actualEnd = (index + 10).coerceAtMost(actual.length)
        return "offset=$index, expected='${expected.substring(start, expectedEnd)}', " +
            "actual='${actual.substring(start, actualEnd)}', lengths=${expected.length}/${actual.length}"
    }

    companion object {
        private val punctuationOnly = setOf("!!! ... — ?")
        private val fixtures: JsonObject by lazy {
            Json
                .parseToJsonElement(
                    File("src/test/resources/kokoro/english-phonemes.json").readText(Charsets.UTF_8),
                ).jsonObject
        }

        @BeforeClass
        @JvmStatic
        fun fixturesExist() {
            assertTrue(
                "Run scripts/kokoro-phonemes-reference.py to write committed fixtures",
                File("src/test/resources/kokoro/english-phonemes.json").isFile,
            )
        }

        private fun phonemizerChunkLimit(): Int = 510
    }
}

class KokoroVocabTest {
    @Test
    fun encodePadsBothEndsAndDropsUnknownSymbols() {
        val vocab = KokoroVocab.load(File("src/main/assets/kokoro/vocab.json").inputStream())
        assertArrayEquals(intArrayOf(0, 43, 16, 44, 0), vocab.encode("a b☃"))
    }

    @Test
    fun encodeRejectsMoreThanTheGraphLimitAfterDroppingUnknownSymbols() {
        val vocab = KokoroVocab.load(File("src/main/assets/kokoro/vocab.json").inputStream())
        assertTrue(runCatching { vocab.encode("a".repeat(511)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(512, vocab.encode("a".repeat(510) + "☃").size)
    }
}

class KokoroPhonemeChunkerTest {
    private val chunker = KokoroPhonemeChunker()

    @Test
    fun sentenceSplitAndPhonemeCutFollowTitlRules() {
        val split = chunker.chunks("One. Two!\nThree?", 4) { it.removeSuffix(".") }
        assertEquals(listOf("One", "Two!", "Thre", "e?"), split.map { it.phonemes })
        assertEquals(listOf("One. ", "Two!\n", "Three?", ""), split.map { it.sourceText })
    }

    @Test
    fun sourceTextPiecesPreserveTheOriginalSentenceSpans() {
        val text = "  First sentence.  Second sentence!\nThird sentence?  "
        val chunks = chunker.chunks(text) { it.replace(" ", "") }
        assertEquals(text, chunks.joinToString("") { it.sourceText })
    }

    @Test
    fun splitUsesLastSpaceThatFitsAndHardCutsWithoutOne() {
        assertEquals(listOf("abc", "defg", "h"), chunker.splitPhonemes("abc defg h", 4))
        assertEquals(listOf("abcd", "efgh", "ij"), chunker.splitPhonemes("abcdefghij", 4))
    }

    @Test
    fun italianDentalBridgeIsNotRemovedForEnglish() {
        assertEquals("t\u032A", chunker.normalizeDentals("t\u032A", "en"))
        assertEquals("t", chunker.normalizeDentals("t\u032A", "it"))
    }
}

class EnglishLexiconTest {
    private val lexicon =
        EnglishLexicon.fromFiles(
            File("src/main/assets/kokoro/us_gold.json.gz"),
            File("src/main/assets/kokoro/us_silver.json.gz"),
        )

    @Test
    fun inflectionRulesCoverSibilantsAndVoicing() {
        assertEquals("kˈæts", lexicon.stemS("cats", "NNS", null, TokenContext()).first)
        assertEquals("wˈɔkt", lexicon.stemEd("walked", "VBD", null, TokenContext()).first)
        assertEquals("ɹˈʌnɪŋ", lexicon.stemIng("running", "VBG", null, TokenContext()).first)
    }

    @Test
    fun specialCasesAndNumberExpansionAreDeterministic() {
        assertEquals("ɐ", lexicon.getWord("a", "DT", null, TokenContext()).first)
        assertEquals("ɐn", lexicon.getWord("an", "DT", null, TokenContext()).first)
        assertEquals("ˌI", lexicon.getWord("I", "PRP", null, TokenContext()).first)
        assertEquals("bˈI", lexicon.getWord("by", "RB", null, TokenContext()).first)
        assertEquals("ɐm", lexicon.getWord("am", "VB", null, TokenContext(false)).first)
        assertEquals("æm", lexicon.getWord("am", "VB", null, TokenContext()).first)
        assertEquals("ˈɪn", lexicon.getWord("in", "IN", null, TokenContext()).first)
        assertEquals("ɪn", lexicon.getWord("in", "IN", null, TokenContext(false)).first)
        assertEquals("ðə", lexicon.getWord("the", "DT", null, TokenContext(false)).first)
        assertEquals("ði", lexicon.getWord("the", "DT", null, TokenContext(true)).first)
        assertEquals("tə", lexicon.getWord("to", "TO", null, TokenContext(false)).first)
        assertEquals("tʊ", lexicon.getWord("to", "TO", null, TokenContext(true)).first)
        assertEquals("jˈust", lexicon.getWord("used", "VBD", null, TokenContext(futureTo = true)).first)
        assertEquals("jˈuzd", lexicon.getWord("used", "VBD", null, TokenContext()).first)
        assertEquals("pəɹsˈɛnt", lexicon.getWord("%", "SYM", null, TokenContext()).first)
        assertEquals("ɹˈid", lexicon.getWord("read", "VBP", null, TokenContext()).first)
        assertEquals("ɹˈɛd", lexicon.getWord("read", "VBD", null, TokenContext()).first)
        assertEquals("ɹˌiɹˈid", lexicon.getWord("reread", "VBP", null, TokenContext()).first)
        assertEquals("wˈund", lexicon.getWord("wound", "VBP", null, TokenContext()).first)
        assertTrue(lexicon.getNumber("21", null, true, "").first.contains("twˈɛnti"))
        assertTrue(lexicon.getNumber("21st", null, true, "").first.isNotBlank())
        assertTrue(lexicon.getNumber("2026", null, true, "").first.isNotBlank())
        assertTrue(lexicon.getNumber("2500000000", "$", true, "").first.isNotBlank())
        assertEquals("ˌɛfbˌiˈI", lexicon.getNnp("FBI").first)
    }
}

class DefaultKokoroPhonemizerTest {
    private val phonemizer =
        DefaultKokoroPhonemizer.fromFiles(
            File("src/main/assets/kokoro/vocab.json"),
            File("src/main/assets/kokoro/us_gold.json.gz"),
            File("src/main/assets/kokoro/us_silver.json.gz"),
            File("src/main/assets/kokoro/en-pos-perceptron.bin"),
        )

    @Test
    fun punctuationOnlyTextProducesNoPlayableChunks() {
        assertTrue(phonemizer.chunks("!!! ... — ?").isEmpty())
    }

    @Test
    fun outOfLexiconEnglishUsesOfflineRuleFallback() {
        val phones = phonemizer.phonemes("blorptastic")
        assertTrue(phones.isNotEmpty())
        assertTrue(phonemizer.encode(phones).size > 2)
    }

    @Test
    fun unknownCompoundsTryLexiconPartsBeforeLetterRules() {
        assertEquals("bˈʊktɹi", phonemizer.phonemes("booktree"))
    }

    @Test
    fun ordinalSuffixesReachTheNumberExpander() {
        assertEquals("fˈɜɹst", phonemizer.phonemes("1st"))
    }

    @Test
    fun parenthesizedIpaAnnotationOverridesTheDictionary() {
        assertEquals("jOsˈɛməTi", phonemizer.phonemes("(Yosemite)[jOsˈɛməTi]"))
        assertEquals(
            "mˈɑtʃu pˈɪtʃu.",
            phonemizer.phonemes("(Machu Picchu)[mˈɑtʃu pˈɪtʃu]."),
        )
    }

    @Test
    fun contractionExpansionMatchesItsExpandedWords() {
        assertEquals(phonemizer.phonemes("I am going home."), phonemizer.phonemes("I'm going home."))
    }

    @Test
    fun sentenceSourceTextRetainsSeparatorsExactly() {
        val text = "The door is near the garden. Alice looked up."
        val chunks = phonemizer.chunks(text)
        assertTrue(chunks.isNotEmpty())
        assertEquals(text, chunks.joinToString("") { it.sourceText })
    }
}

class EnglishPosTaggerTest {
    @Test
    fun openNlpReturnsAlignedPennTagsFromThePinnedModel() {
        val tagger = OpenNlpEnglishPosTagger(File("src/main/assets/kokoro/en-pos-perceptron.bin").inputStream())
        val tags = tagger.tag(listOf("Yesterday", "I", "read", "the", "book", "."))

        assertEquals(6, tags.size)
        assertEquals("PRP", tags[1])
        assertEquals("VBD", tags[2])
        assertEquals("DT", tags[3])
    }
}
