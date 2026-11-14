# The Vulkan renderer of the mesh gradient background. The shaders are embedded in shaders_spv.h,
# compile_shaders.sh rebuilds it from shaders/ when they change.
cmake_minimum_required(VERSION 3.22.1)

project(pelican_vulkan CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pelican_vulkan SHARED
    vulkan_mesh.cpp
    vulkan_mesh_jni.cpp)

target_compile_definitions(pelican_vulkan PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)
target_compile_options(pelican_vulkan PRIVATE -Wall -Wextra -Wno-missing-field-initializers)

# libvulkan exists from Android 7 (API 24). The Gradle build sets ANDROID_PLATFORM to 24 for this library,
# on older phones it does not load and the app uses OpenGL ES.
target_link_libraries(pelican_vulkan vulkan android log)
