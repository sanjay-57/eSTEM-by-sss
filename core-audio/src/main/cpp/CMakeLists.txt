cmake_minimum_required(VERSION 3.22.1)
project(estem_audio)

find_package(oboe REQUIRED CONFIG)

add_library(estem_audio SHARED
        jni_bridge.cpp
        engine.cpp
        stem_deck.cpp
        stretcher.cpp
        effects.cpp
        recorder.cpp
)

target_link_libraries(estem_audio
        oboe::oboe
        android
        log
)

# Android 15+ devices can use 16 KB memory pages. A library aligned to the old 4 KB boundary
# triggers a compatibility warning now and will fail to load on future releases.
target_link_options(estem_audio PRIVATE "-Wl,-z,max-page-size=16384")
