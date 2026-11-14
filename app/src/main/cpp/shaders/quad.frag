#version 450
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 outColor;
layout(set = 0, binding = 0) uniform sampler2D u_texture;
layout(push_constant) uniform Params {
    float alpha;
} pc;

void main() {
    vec4 color = texture(u_texture, v_uv);
    outColor = vec4(color.rgb, color.a * pc.alpha);
}
