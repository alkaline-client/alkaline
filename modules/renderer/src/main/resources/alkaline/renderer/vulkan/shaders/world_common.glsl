// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
// Shared by the terrain stages: the push constants and vanilla 1.8.9's lightmap filtering.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Origins { vec4 values[]; };
// Per-frame table: [0, 256) lightmap texels (sRGB RGBA8, index sky * 16 + block); from 256, the array layer each
// block texture samples this frame (animation advances by changing these entries).
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer FrameTable { uint values[]; };
layout(push_constant) uniform Scene {
    mat4 viewProjection;  // camera-relative: rotation and projection only
    vec4 fogColor;        // rgb: linear fog color; w: fog start in blocks
    vec4 params;          // x: fog end in blocks; y: 1 when fog is enabled; z: exponential fog density, or 0
    Origins origins;      // per draw (instance index): section minimum corner relative to the camera
    FrameTable table;
} pc;

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

vec3 lightmapTexel(float sky, float block) {
    return unpackUnorm4x8(pc.table.values[int(sky) * 16 + int(block)]).rgb;
}

// levels.x = block light, levels.y = sky light, both 0..15 and fractional from smooth lighting. Vanilla samples
// its 16x16 lightmap texture with linear filtering, which blends the four nearest whole levels; so does this.
// The table is computed once per frame on the CPU (Lightmap.java).
vec3 lightmap(vec2 levels) {
    // Helper invocations (the unused pixels of a 2x2 quad) extrapolate varyings past the triangle, so levels
    // can leave 0..15 slightly; clamp like the texture sampler's edge mode before indexing the table.
    levels = clamp(levels, vec2(0.0), vec2(15.0));
    vec2 low = floor(levels);
    vec2 high = min(low + 1.0, vec2(15.0));
    vec2 t = levels - low;
    vec3 bottom = mix(lightmapTexel(low.y, low.x), lightmapTexel(low.y, high.x), t.x);
    vec3 top = mix(lightmapTexel(high.y, low.x), lightmapTexel(high.y, high.x), t.x);
    return mix(bottom, top, t.y);
}
