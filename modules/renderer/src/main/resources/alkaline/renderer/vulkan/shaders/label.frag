// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Texel times vertex color with an alpha test (vanilla's GREATER 0.1 unless the draw asks otherwise). Color math runs on encoded values like vanilla GL
// and is linearized at the end when the target re-encodes to sRGB.
layout(set = 0, binding = 0) uniform sampler2D image;
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    vec4 flags;   // x: 1 when output must be linearized; y: alpha at or below which texels are discarded
} scene;
layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 color;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec4 texel = texture(image, uv) * color;
    if (texel.a <= scene.flags.y) {
        discard;
    }
    outColor = vec4(scene.flags.x != 0.0 ? toLinear(texel.rgb) : texel.rgb, texel.a);
}
