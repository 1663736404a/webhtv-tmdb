cmake_minimum_required(VERSION 3.22.1)

project(exo_dovi_renderer LANGUAGES CXX)

add_library(exo_dovi_renderer SHARED exo_dovi_renderer.cpp)

target_compile_features(exo_dovi_renderer PRIVATE cxx_std_20)
target_compile_options(exo_dovi_renderer PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden)
target_link_options(exo_dovi_renderer PRIVATE -Wl,--no-undefined)

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)
find_library(media-ndk-lib mediandk REQUIRED)
find_library(vulkan-lib vulkan REQUIRED)

target_link_libraries(exo_dovi_renderer PRIVATE
        ${android-lib}
        ${log-lib}
        ${media-ndk-lib}
        ${vulkan-lib})
