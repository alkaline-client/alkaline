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

vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, greaterThan(c, vec3(0.0031308)));
}

// Vanilla's fixed-function fog blends the gamma-encoded color toward the fog color. Blending linear values instead
// lets a bright surface (lava, a torch-lit wall) shine through dark fog, two or three times too bright in the Nether.
vec3 fogMix(vec3 linear, float fog) {
    if (fog <= 0.0) {
        return linear;
    }
    return srgbToLinear(mix(linearToSrgb(linear), linearToSrgb(pc.fogColor.rgb), fog));
}

// Vanilla's 16x16 lightmap (x: block light, y: sky light), gamma-encoded, computed on the CPU when its inputs change
// (Lightmap.java), linearly filtered and clamped at the edges.
layout(set = 2, binding = 0) uniform sampler2D lightmapImage;

// levels.x = block light, levels.y = sky light, both 0..15 and fractional from smooth lighting. Vanilla samples its
// lightmap texture with linear filtering at (level * 16 + 8) / 256, the texel centers, which blends the four nearest
// whole levels; so does this, with the same fixed-point filter weights.
vec3 lightmap(vec2 levels) {
    return texture(lightmapImage, (levels + 0.5) / 16.0).rgb;
}
