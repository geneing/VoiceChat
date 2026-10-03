/*
 * Port of the pinned LiteRT speech-recognition sample's tokenizer JNI binding
 * (HuggingfaceTokenizer.kt). Original sample source is Apache-2.0:
 * https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
 * Pinned at scripts/litert-sample-source.commit.
 *
 * Copyright 2026 Google LLC.
 * Licensed under the Apache License, Version 2.0.
 */
package com.voicechat.agent.eval.litert

/**
 * Decodes token IDs into text using the sample's native HuggingFace tokenizer.
 *
 * The native bridge is `libtokenizer_jni.so`, built from the pinned sample's
 * `tokenizer_jni.cc` by `scripts/build-tokenizer-jni.sh`. It is debug/evaluation
 * only and loaded lazily so a JVM that never touches tokenizer text is unaffected.
 */
internal class LiteRtHuggingfaceTokenizer private constructor(
    private val nativeHandle: Long,
) : AutoCloseable {
    val vocabSize: Int = nativeGetVocabSize(nativeHandle)

    fun decode(
        tokenIds: IntArray,
        skipSpecialTokens: Boolean = true,
    ): String = nativeDecode(nativeHandle, tokenIds, skipSpecialTokens).trim()

    override fun close() {
        if (nativeHandle != 0L) nativeFree(nativeHandle)
    }

    companion object {
        /** Loads the JNI bridge; throws if the debug library is not packaged. */
        fun load() {
            System.loadLibrary("tokenizer_jni")
        }

        /** Builds a tokenizer from the raw `tokenizer.json` payload (HuggingFace format). */
        fun fromJson(json: String): LiteRtHuggingfaceTokenizer {
            load()
            val handle = nativeInit(json)
            check(handle != 0L) { "Failed to initialize the native HuggingFace tokenizer." }
            return LiteRtHuggingfaceTokenizer(handle)
        }

        @JvmStatic private external fun nativeInit(jsonPayload: String): Long

        @JvmStatic private external fun nativeFree(handle: Long)

        @JvmStatic private external fun nativeDecode(
            handle: Long,
            ids: IntArray,
            skipSpecialTokens: Boolean,
        ): String

        @JvmStatic private external fun nativeGetVocabSize(handle: Long): Int
    }
}
