// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16 (as entity.*), renamed 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Model surface: texels at or below 0.1 alpha are discarded, the rest blend. Color math runs on encoded values like vanilla GL and is linearized at the end when
// the swapchain re-encodes to sRGB.
#extension GL_EXT_buffer_reference : require
layout(set = 0, binding = 0) uniform sampler2D skin;
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Fog { vec4 color; vec4 params; };
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    uvec2 instances;
    uvec2 matrices;
    vec4 light0;
    vec4 light1;
    Fog fog;
} scene;
layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 tint;
layout(location = 2) in vec4 overlay;
layout(location = 3) in vec3 shade;
layout(location = 4) in float fog;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec4 color = texture(skin, uv) * tint;
    if (color.a <= 0.1) {
        discard;
    }
    color.rgb = mix(color.rgb, overlay.rgb, overlay.a) * shade;
    vec3 linear = scene.light0.w != 0.0 ? toLinear(color.rgb) : color.rgb;
    outColor = vec4(mix(linear, scene.fog.color.rgb, fog), color.a);
}
