// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16, revised 2026-10-18.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_GOOGLE_include_directive : require
// Terrain vertex: four packed words (see ChunkMesh). Positions are section-local, and the section's origin
// relative to the camera comes per draw through the instance index, so vertices keep full float precision at
// any world coordinate.
#include "world_common.glsl"
layout(location = 0) in uvec4 packed;
layout(location = 0) out vec3 texcoord;
layout(location = 1) out vec3 color;
layout(location = 2) out vec2 light;
layout(location = 3) out float fog;
layout(location = 4) flat out uint tileArray;

void main() {
    vec3 local = vec3(float(packed.x & 0x3FFFu), float((packed.x >> 14) & 0x3FFFu), float(packed.y & 0x3FFFu))
            / 512.0 - 8.0;
    vec3 relative = pc.origins.values[gl_InstanceIndex].xyz + local;
    gl_Position = pc.viewProjection * vec4(relative, 1.0);
    // The texture id (11 low bits in z, 5 high bits atop y) maps through the frame table to array << 16 | layer.
    uint entry = pc.table.values[256u + (((packed.z >> 13) & 0x7FFu) | ((packed.y >> 27) << 11))];
    texcoord = vec3(float((packed.y >> 14) & 0x1FFFu) / 256.0, float(packed.z & 0x1FFFu) / 256.0,
            float(entry & 0xFFFFu));
    tileArray = entry >> 16;
    // Vanilla's baked vertex color (tint, shade, occlusion) is sRGB; light is in lightmap units (1/16 level).
    color = srgbToLinear(unpackUnorm4x8(packed.w).rgb);
    light = vec2(float(packed.z >> 24), float(packed.w >> 24)) / 16.0;
    // Vanilla's fog by radial distance (NV_fog_distance): linear from start to end, or exponential (params.z
    // density) in water and lava.
    float distance = length(relative);
    fog = pc.params.y < 0.5 ? 0.0 : pc.params.z > 0.0 ? 1.0 - exp(-pc.params.z * distance)
            : clamp((distance - pc.fogColor.w) / max(pc.params.x - pc.fogColor.w, 1.0), 0.0, 1.0);
}
