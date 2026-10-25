cmake_minimum_required(VERSION 3.22)
project(ltots_native C CXX)

# Rubber Band Library (GPL v2+) — high-quality pitch shifting and time stretching.
# Built as a single compilation unit with its own FFT and resampler, plus the JNI bindings.
set(RB ${CMAKE_CURRENT_SOURCE_DIR}/rubberband)

add_library(rubberband-jni SHARED
    ${RB}/single/RubberBandSingle.cpp
    ${RB}/src/jni/RubberBandStretcherJNI.cpp)

target_include_directories(rubberband-jni PRIVATE ${RB} ${RB}/src)
target_compile_features(rubberband-jni PRIVATE cxx_std_17)
target_compile_options(rubberband-jni PRIVATE -O3 -Wno-unused-parameter)
# Android 15+ devices may use 16 KB memory pages.
target_link_options(rubberband-jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rubberband-jni PRIVATE log)

# whisper.cpp (MIT) — on-device speech recognition for the song finder's lyrics listener.
# Fetched at a pinned release when the app is built. LTOTS_WHISPER_SRC can point at a local copy.
include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Dot-product instructions (every phone since ~2019); the app checks for them before loading.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
if(DEFINED ENV{LTOTS_WHISPER_SRC})
    set(FETCHCONTENT_SOURCE_DIR_WHISPER $ENV{LTOTS_WHISPER_SRC})
endif()
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG d1be6fde11ac6e0407606b4e42fe72d34add8037 # v1.9.5
    GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(whisper)

add_library(whisper-jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/whisper/whisper-jni.cpp)
target_compile_features(whisper-jni PRIVATE cxx_std_17)
target_compile_options(whisper-jni PRIVATE -O3)
target_link_options(whisper-jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(whisper-jni PRIVATE whisper log)
