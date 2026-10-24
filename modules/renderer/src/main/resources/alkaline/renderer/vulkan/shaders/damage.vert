// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-23.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_GOOGLE_include_directive : require
// The crack overlay of a block being dug: the block's own terrain quads (same packed vertex as world.vert) with
// a destroy_stage texture, no light, color or fog.
#include "world_common.glsl"
layout(location = 0) in uvec4 packed;
layout(location = 0) out vec3 texcoord;
layout(location = 1) flat out uint tileArray;

void main() {
    vec3 local = vec3(float(packed.x & 0x3FFFu), float((packed.x >> 14) & 0x3FFFu), float(packed.y & 0x3FFFu))
            / 512.0 - 8.0;
    vec3 relative = pc.origins.values[gl_InstanceIndex].xyz + local;
    // Vanilla draws the overlay with polygon offset (-3, -3) so it wins the depth test against the block it
    // covers; a few millimeters toward the eye does the same.
    relative -= normalize(relative) * 0.004;
    gl_Position = pc.viewProjection * vec4(relative, 1.0);
    uint entry = pc.table.values[256u + (((packed.z >> 13) & 0x7FFu) | ((packed.y >> 27) << 11))];
    texcoord = vec3(float((packed.y >> 14) & 0x1FFFu) / 256.0, float(packed.z & 0x1FFFu) / 256.0,
            float(entry & 0xFFFFu));
    tileArray = entry >> 16;
}
