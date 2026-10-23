// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
layout(set = 0, binding = 0) uniform sampler2D sheet;
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    vec4 right;
    vec4 up;
} scene;
layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 color;
layout(location = 2) flat in uint layer;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec4 texel = texture(sheet, uv) * color;
    // Vanilla particles use alphaFunc(GREATER, 1/255).
    if (texel.a <= 0.003921569) {
        discard;
    }
    outColor = vec4(scene.up.w != 0.0 ? toLinear(texel.rgb) : texel.rgb, texel.a);
}
