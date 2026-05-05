cmake_minimum_required(VERSION 3.22.1)

project(quest_lsl_bridge LANGUAGES CXX)

add_library(lsl SHARED IMPORTED)
set_target_properties(
    lsl
    PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/liblsl.so"
)

add_library(
    quest_lsl_bridge
    SHARED
    quest_lsl_bridge.cpp
)

target_compile_features(quest_lsl_bridge PRIVATE cxx_std_17)
target_include_directories(quest_lsl_bridge PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_options(
    quest_lsl_bridge
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)

target_link_libraries(
    quest_lsl_bridge
    PRIVATE
    lsl
    ${log-lib}
)
