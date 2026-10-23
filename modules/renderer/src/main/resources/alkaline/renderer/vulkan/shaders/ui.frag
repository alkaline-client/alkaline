// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Composites the Compose layer: premultiplied, sRGB-encoded BGRA from Skia. Encoded premultiplied values cannot
// be linearized directly, so the color is unpremultiplied, decoded, and premultiplied again in linear space.
layout(set = 0, binding = 0) uniform sampler2D layer;
layout(push_constant) uniform Ui {
    int linearOutput;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec4 texel = texture(layer, uv);
    if (texel.a <= 0.0) {
        discard;
    }
    if (pc.linearOutput != 0) {
        vec3 straight = clamp(texel.rgb / texel.a, 0.0, 1.0);
        texel.rgb = toLinear(straight) * texel.a;
    }
    outColor = texel;
}
