// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-23.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_GOOGLE_include_directive : require
// The crack texture of RenderGlobal.drawBlockDamageTexture. Vanilla blends it as src * dst + dst * src (GL_DST_COLOR,
// GL_SRC_COLOR) on its sRGB-encoded framebuffer, so the cracks darken by twice the texel and lighten nothing; it draws
// with color (1, 1, 1, 0.5), so texels with alpha at or below 0.2 fail its 0.1 alpha test. The pass normally runs on the UNORM alias of the color attachment, so the
// blend multiplies encoded values exactly as vanilla does and the texel goes out re-encoded (flag 1 clear). Without
// an alias (flag 1 set) the blend runs in linear light and the texel goes out as sampled.
#include "world_common.glsl"
layout(set = 0, binding = 0) uniform sampler2DArray blocks;
layout(set = 1, binding = 0) uniform sampler2DArray tiles;
layout(location = 0) in vec3 texcoord;
layout(location = 1) flat in uint tileArray;
layout(location = 0) out vec4 outColor;

vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, greaterThan(c, vec3(0.0031308)));
}

void main() {
    vec4 texel = tileArray == 0u ? texture(blocks, texcoord) : texture(tiles, texcoord);
    if (texel.a * 0.5 <= 0.1) {
        discard;
    }
    // params.w is 1 when the blend runs in linear light.
    outColor = vec4(pc.params.w > 0.5 ? texel.rgb : linearToSrgb(texel.rgb), texel.a);
}
