// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// "Fluid" menu background: domain-warped noise carried by the simulated flow, plus the dye streams, drawn as
// an ordered (Bayer 8x8) dither of ink over the theme surface in square cells.
layout(set = 0, binding = 0) uniform sampler2D state;  // rg velocity, b dye (see fluid_step.frag)
layout(push_constant) uniform Composite {
    vec4 surface;     // rgb: linear base color
    vec4 ink;         // rgb: linear ink color; a: ink strength
    vec2 resolution;  // target size in pixels
    float time;
    float cellSize;   // dither cell, pixels
    float dyeStrength;
    float noiseStrength;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

// Classic 2D gradient noise (Gustavson's permutation-polynomial formulation).
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
vec2 fade(vec2 t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

float gradientNoise(vec2 p) {
    vec4 pi = mod289(floor(p.xyxy) + vec4(0.0, 0.0, 1.0, 1.0));
    vec4 pf = fract(p.xyxy) - vec4(0.0, 0.0, 1.0, 1.0);
    vec4 i = permute(permute(pi.xzxz) + pi.yyww);
    vec4 gx = fract(i * (1.0 / 41.0)) * 2.0 - 1.0;
    vec4 gy = abs(gx) - 0.5;
    gx -= floor(gx + 0.5);
    vec2 g00 = vec2(gx.x, gy.x), g10 = vec2(gx.y, gy.y), g01 = vec2(gx.z, gy.z), g11 = vec2(gx.w, gy.w);
    vec4 norm = taylorInvSqrt(vec4(dot(g00, g00), dot(g01, g01), dot(g10, g10), dot(g11, g11)));
    g00 *= norm.x; g01 *= norm.y; g10 *= norm.z; g11 *= norm.w;
    float n00 = dot(g00, pf.xy);
    float n10 = dot(g10, pf.zy);
    float n01 = dot(g01, pf.xw);
    float n11 = dot(g11, pf.zw);
    vec2 f = fade(pf.xy);
    vec2 nx = mix(vec2(n00, n01), vec2(n10, n11), f.x);
    return 2.3 * mix(nx.x, nx.y, f.y);
}

// Ridged turbulence: four octaves of |noise|, each a third the size and weight of the last.
float turbulence(vec2 p) {
    float value = 0.0;
    float amplitude = 1.0;
    for (int octave = 0; octave < 4; octave++) {
        value += amplitude * abs(gradientNoise(p));
        p *= 3.0;
        amplitude *= 0.3;
    }
    return value;
}

float bayer8(ivec2 cell) {
    // The threshold order of the nice-elements original, so the pattern matches it cell for cell.
    const int matrix[64] = int[64](
        0, 48, 12, 60, 3, 51, 15, 63, 32, 16, 44, 28, 35, 19, 47, 31,
        8, 56, 4, 52, 11, 59, 7, 55, 40, 24, 36, 20, 43, 27, 39, 23,
        2, 50, 14, 62, 1, 49, 13, 61, 34, 18, 46, 30, 33, 17, 45, 29,
        10, 58, 6, 54, 9, 57, 5, 53, 42, 26, 38, 22, 41, 25, 37, 21);
    ivec2 p = cell & 7;
    return float(matrix[p.y * 8 + p.x]) / 64.0;
}

void main() {
    float cellSize = max(pc.cellSize, 1.0);
    ivec2 cell = ivec2(floor(uv * pc.resolution / cellSize));
    vec2 cellUv = (vec2(cell) + 0.5) * cellSize / pc.resolution;
    vec2 centered = (cellUv - 0.5) * vec2(pc.resolution.x / pc.resolution.y, 1.0);
    vec4 field = texture(state, cellUv);
    vec2 advected = centered - field.xy * 0.018;
    float noise = turbulence(advected + turbulence(advected - vec2(pc.time * 0.05)));
    float density = clamp(noise * pc.noiseStrength - 0.13, 0.0, 1.0);
    density = max(density, clamp(field.b * pc.dyeStrength, 0.0, 1.0));
    // The pattern is anchored to the bottom row, as in the original.
    ivec2 cellCount = ivec2(ceil(pc.resolution / cellSize));
    float lit = step(bayer8(ivec2(cell.x, cellCount.y - 1 - cell.y)), density) * pc.ink.a;
    outColor = vec4(mix(pc.surface.rgb, pc.ink.rgb, lit), 1.0);
}
