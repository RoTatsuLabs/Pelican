#version 450
// Vulkan version of the AMLL mesh gradient fragment shader (packages/core/src/bg-render/mesh-renderer/mesh.frag.glsl)
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D u_texture;

layout(push_constant) uniform Params {
    float aspect;
    float volume;
    float sinAngle;
    float cosAngle;
    float vignette;
    float grain;
} pc;

const float INV_255 = 1.0 / 255.0;
const float HALF_INV_255 = 0.5 / 255.0;
const float GRADIENT_NOISE_A = 52.9829189;
const vec2 GRADIENT_NOISE_B = vec2(0.06711056, 0.00583715);

float gradientNoise(in vec2 uv) {
    return fract(GRADIENT_NOISE_A * fract(dot(uv, GRADIENT_NOISE_B)));
}

void main() {
    float volumeEffect = pc.volume * 2.0;

    float dither = (INV_255 * gradientNoise(gl_FragCoord.xy) - HALF_INV_255) * pc.grain;

    vec2 centeredUV = v_uv - vec2(0.2);

    vec2 rotatedUV = vec2(
        pc.cosAngle * centeredUV.x - pc.sinAngle * centeredUV.y,
        pc.sinAngle * centeredUV.x + pc.cosAngle * centeredUV.y
    );

    vec2 finalUV = rotatedUV * max(0.001, 1.0 - volumeEffect) + vec2(0.5);

    vec4 result = texture(u_texture, finalUV);

    float alphaVolumeFactor = max(0.5, 1.0 - pc.volume * 0.5);
    result.rgb *= alphaVolumeFactor;
    result.a *= alphaVolumeFactor;

    result.rgb += vec3(dither);

    float dist = distance(v_uv, vec2(0.5));
    float vignette = smoothstep(0.8, 0.3, dist);
    float mask = 1.0 - (1.0 - vignette) * 0.4 * pc.vignette;
    result.rgb *= mask;

    outColor = result;
}
