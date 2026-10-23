// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Rain and snow (EntityRenderer.renderRainSnow): texture times the column's lightmap color and fade, with
// vanilla's alpha test at 0.1, blended in gamma space.
layout(set = 0, binding = 0) uniform sampler2D image;
layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 color;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texel = texture(image, uv) * color;
    if (texel.a <= 0.1) {
        discard;
    }
    outColor = texel;
}
