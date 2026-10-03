cmake_minimum_required(VERSION 3.22.1)
project(tokenizer_jni)

# VOICECHAT_TOKENIZER_SOURCE_DIR / VOICECHAT_TOKENIZER_SAMPLE_CPP_DIR are passed
# by scripts/build-tokenizer-jni.sh so this file does not hardcode a checkout
# path. Normalize Windows-style values so a backslash never reaches a CMake
# string literal (which rejects '\A').
file(TO_CMAKE_PATH "${VOICECHAT_TOKENIZER_SOURCE_DIR}" VOICECHAT_TOKENIZER_SOURCE_DIR)
file(TO_CMAKE_PATH "${VOICECHAT_TOKENIZER_SAMPLE_CPP_DIR}" VOICECHAT_TOKENIZER_SAMPLE_CPP_DIR)

add_library(tokenizer_jni SHARED
    ${VOICECHAT_TOKENIZER_SOURCE_DIR}/tokenizer_jni.cc
)

# The header (tokenizers_c.h) and the prebuilt static lib come from the pinned
# sample checkout, which lives outside this repo and is fetched by
# scripts/fetch-litert-speech-sample.sh.
target_include_directories(tokenizer_jni PRIVATE
    ${VOICECHAT_TOKENIZER_SAMPLE_CPP_DIR}/include
)

set(JNILIBS_DIR ${VOICECHAT_TOKENIZER_SAMPLE_CPP_DIR}/lib/${ANDROID_ABI})
add_library(tokenizers_c STATIC IMPORTED)
set_target_properties(tokenizers_c PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/libtokenizers_c.a
)

target_link_libraries(tokenizer_jni
    tokenizers_c
    log
)
