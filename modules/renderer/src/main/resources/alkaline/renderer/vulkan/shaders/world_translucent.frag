// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16, revised 2026-10-18.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_GOOGLE_include_directive : require
// Blended terrain (water): straight alpha, fading out into the fog. Drawn back to front.
#include "world_common.glsl"
layout(set = 0, binding = 0) uniform sampler2DArray blocks;
// Connected-texture tiles that did not fit the first array.
layout(set = 1, binding = 0) uniform sampler2DArray tiles;
layout(location = 0) in vec3 texcoord;
layout(location = 1) in vec3 color;
layout(location = 2) in vec2 light;
layout(location = 3) in float fog;
layout(location = 4) flat in uint tileArray;
layout(location = 0) out vec4 outColor;

vec3 lit(vec3 albedo) {
    return albedo * color * srgbToLinear(lightmap(light));
}

void main() {
    vec4 texel = (tileArray == 0u ? texture(blocks, texcoord) : texture(tiles, texcoord));
    outColor = vec4(fogMix(lit(texel.rgb), fog), texel.a * (1.0 - fog * fog));
}
