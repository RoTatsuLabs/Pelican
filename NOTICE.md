# Third-party code

## Apple Music-like Lyrics (AMLL)

The mesh gradient background of the playback page is ported to Kotlin from
[Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)
by SteveXMH and the AMLL contributors, which is licensed under the GNU Affero General Public License v3.0.

Ported files, with the AMLL files they come from:

| Pelican | AMLL (`packages/core/src/bg-render`) |
| --- | --- |
| `ui/widgets/effects/mesh/MeshPresets.kt` | `mesh-renderer/cp-presets.ts` |
| `ui/widgets/effects/mesh/MeshGradientMath.kt` | `mesh-renderer/index.ts`, `mesh-renderer/cp-generate.ts`, `img.ts` |
| `ui/widgets/effects/mesh/MeshBackend.kt` | `mesh-renderer/index.ts` (the fade between covers, the flow of time) |
| `ui/widgets/effects/mesh/MeshGradientRenderer.kt` | `mesh-renderer/index.ts`, `mesh-renderer/mesh.vert.glsl`, `mesh-renderer/mesh.frag.glsl` (OpenGL ES) |
| `cpp/vulkan_mesh.cpp`, `cpp/shaders/` | `mesh-renderer/index.ts`, `mesh-renderer/mesh.vert.glsl`, `mesh-renderer/mesh.frag.glsl` (Vulkan) |

`MeshGradientView.kt`, `BassAnalyzer.kt`, `VulkanMeshNative.kt` and `cpp/vulkan_mesh_jni.cpp` are new code written for Pelican.
