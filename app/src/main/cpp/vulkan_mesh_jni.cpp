/*
 * Mesh gradient background, ported to Kotlin and C++ from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The JNI bridge is new code written for Pelican. It matches VulkanMeshNative.kt.
 */

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cmath>
#include <vector>

#include "vulkan_mesh.h"

namespace {

struct Handle {
    pelican::MeshRenderer* renderer;
    ANativeWindow* window;
};

Handle* handleOf(jlong value) { return reinterpret_cast<Handle*>(value); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_yos_music_player_ui_widgets_effects_mesh_VulkanMeshNative_create(
    JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    pelican::MeshRenderer* renderer = pelican::MeshRenderer::create(window, width, height);
    if (!renderer) {
        ANativeWindow_release(window);
        return 0;
    }
    return reinterpret_cast<jlong>(new Handle{renderer, window});
}

JNIEXPORT void JNICALL Java_yos_music_player_ui_widgets_effects_mesh_VulkanMeshNative_destroy(JNIEnv*, jobject,
                                                                                              jlong value) {
    Handle* handle = handleOf(value);
    if (!handle) return;
    // The renderer owns the Vulkan surface, so it goes first
    delete handle->renderer;
    ANativeWindow_release(handle->window);
    delete handle;
}

JNIEXPORT jint JNICALL Java_yos_music_player_ui_widgets_effects_mesh_VulkanMeshNative_addState(
    JNIEnv* env, jobject, jlong value, jfloatArray vertices, jshortArray indices, jintArray cover) {
    Handle* handle = handleOf(value);
    if (!handle) return -1;
    const jsize vertexFloats = env->GetArrayLength(vertices);
    const jsize indexCount = env->GetArrayLength(indices);
    const jsize pixelCount = env->GetArrayLength(cover);
    const jsize side = static_cast<jsize>(std::lround(std::sqrt(static_cast<double>(pixelCount))));
    if (vertexFloats % 4 != 0 || side * side != pixelCount) return -1;

    jfloat* vertexData = env->GetFloatArrayElements(vertices, nullptr);
    jshort* indexData = env->GetShortArrayElements(indices, nullptr);
    jint* pixels = env->GetIntArrayElements(cover, nullptr);
    jint id = -1;
    if (vertexData && indexData && pixels) {
        // The cover is ARGB integers, the renderer takes RGBA bytes
        std::vector<uint8_t> rgba(static_cast<size_t>(pixelCount) * 4);
        for (jsize i = 0; i < pixelCount; i++) {
            const uint32_t c = static_cast<uint32_t>(pixels[i]);
            rgba[i * 4] = (c >> 16) & 0xFF;
            rgba[i * 4 + 1] = (c >> 8) & 0xFF;
            rgba[i * 4 + 2] = c & 0xFF;
            rgba[i * 4 + 3] = (c >> 24) & 0xFF;
        }
        id = handle->renderer->addState(vertexData, vertexFloats / 4, reinterpret_cast<uint16_t*>(indexData),
                                        indexCount, rgba.data(), side);
    }
    if (vertexData) env->ReleaseFloatArrayElements(vertices, vertexData, JNI_ABORT);
    if (indexData) env->ReleaseShortArrayElements(indices, indexData, JNI_ABORT);
    if (pixels) env->ReleaseIntArrayElements(cover, pixels, JNI_ABORT);
    return id;
}

JNIEXPORT void JNICALL Java_yos_music_player_ui_widgets_effects_mesh_VulkanMeshNative_removeState(
    JNIEnv*, jobject, jlong value, jint id) {
    Handle* handle = handleOf(value);
    if (handle) handle->renderer->removeState(id);
}

JNIEXPORT jboolean JNICALL Java_yos_music_player_ui_widgets_effects_mesh_VulkanMeshNative_draw(
    JNIEnv* env, jobject, jlong value, jintArray ids, jfloatArray alphas, jint count, jfloat sinAngle, jfloat cosAngle,
    jfloat volume, jfloat vignette, jfloat grain) {
    Handle* handle = handleOf(value);
    if (!handle) return JNI_FALSE;
    if (count < 0 || count > env->GetArrayLength(ids) || count > env->GetArrayLength(alphas)) return JNI_FALSE;
    jint* idData = env->GetIntArrayElements(ids, nullptr);
    jfloat* alphaData = env->GetFloatArrayElements(alphas, nullptr);
    bool drawn = false;
    if (idData && alphaData) {
        drawn = handle->renderer->draw(idData, alphaData, count, sinAngle, cosAngle, volume, vignette, grain);
    }
    if (idData) env->ReleaseIntArrayElements(ids, idData, JNI_ABORT);
    if (alphaData) env->ReleaseFloatArrayElements(alphas, alphaData, JNI_ABORT);
    return drawn ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
