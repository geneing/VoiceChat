// JNI bridge for the LiteRT speech-recognition sample's HuggingFace tokenizer.
//
// This is our own thin shim with JNI symbol names matching
// com.voicechat.agent.eval.litert.LiteRtHuggingfaceTokenizer. The tokenizer
// implementation itself is the pinned sample's prebuilt libtokenizers_c.a, linked
// here unchanged; only the C++ symbol names differ from the sample's file, so the
// sample checkout is not used verbatim (it also lives outside this repo).
//
// Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0.
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "tokenizers_c.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_voicechat_agent_eval_litert_LiteRtHuggingfaceTokenizer_nativeInit(
    JNIEnv* env, jobject thiz, jstring json_payload) {
  const char* json_ptr = env->GetStringUTFChars(json_payload, nullptr);
  std::string json_str(json_ptr);
  env->ReleaseStringUTFChars(json_payload, json_ptr);

  auto tokenizer = tokenizers_new_from_str(json_str.c_str(), json_str.size());
  return reinterpret_cast<jlong>(tokenizer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicechat_agent_eval_litert_LiteRtHuggingfaceTokenizer_nativeFree(
    JNIEnv* env, jobject thiz, jlong handle) {
  auto* tokenizer = reinterpret_cast<TokenizerHandle>(handle);
  tokenizers_free(tokenizer);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voicechat_agent_eval_litert_LiteRtHuggingfaceTokenizer_nativeDecode(
    JNIEnv* env, jobject thiz, jlong handle, jintArray ids,
    jboolean skip_special_tokens) {
  auto* tokenizer = reinterpret_cast<TokenizerHandle>(handle);
  if (!tokenizer) return env->NewStringUTF("");

  jsize ids_len = env->GetArrayLength(ids);
  jint* ids_ptr = env->GetIntArrayElements(ids, nullptr);
  std::vector<uint32_t> token_ids(ids_ptr, ids_ptr + ids_len);
  env->ReleaseIntArrayElements(ids, ids_ptr, JNI_ABORT);

  tokenizers_decode(tokenizer, token_ids.data(), token_ids.size(),
                    static_cast<int>(skip_special_tokens));

  const char* decode_str;
  size_t decode_str_len;
  tokenizers_get_decode_str(tokenizer, &decode_str, &decode_str_len);
  std::string decode_str_cpp(decode_str, decode_str_len);
  return env->NewStringUTF(decode_str_cpp.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicechat_agent_eval_litert_LiteRtHuggingfaceTokenizer_nativeGetVocabSize(
    JNIEnv* env, jobject thiz, jlong handle) {
  auto* tokenizer = reinterpret_cast<TokenizerHandle>(handle);
  if (!tokenizer) return 0;

  size_t vocab_size;
  tokenizers_get_vocab_size(tokenizer, &vocab_size);
  return vocab_size;
}
