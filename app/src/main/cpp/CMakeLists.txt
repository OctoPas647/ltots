cmake_minimum_required(VERSION 3.22)
project(ltots_native CXX)

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
