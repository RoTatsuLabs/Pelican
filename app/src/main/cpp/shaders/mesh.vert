#version 450
// Vulkan version of the AMLL mesh gradient vertex shader (packages/core/src/bg-render/mesh-renderer/mesh.vert.glsl)
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 0) out vec2 v_uv;

layout(push_constant) uniform Params {
    float aspect;
    float volume;
    float sinAngle;
    float cosAngle;
    float vignette;
    float grain;
} pc;

void main() {
    v_uv = a_uv;
    vec2 pos = a_pos;
    if (pc.aspect > 1.0) {
        pos.y *= pc.aspect;
    } else {
        pos.x /= pc.aspect;
    }
    // Clip space points down in Vulkan, the OpenGL version points up
    gl_Position = vec4(pos.x, -pos.y, 0.0, 1.0);
}
