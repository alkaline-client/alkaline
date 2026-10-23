// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Dual-filter (Kawase) upsample: eight taps around a tent of the smaller source.
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Blur {
    vec2 sourceTexel;
    float offset;
    vec2 targetTexel;  // 1 / target size
} pc;
layout(location = 0) in vec2 rectUv;
layout(location = 0) out vec4 outColor;
void main() {
    // From the pixel position rather than the interpolated rectangle, so a pixel samples the same spots however
    // the pass's rectangles are cut.
    vec2 uv = gl_FragCoord.xy * pc.targetTexel;
    vec2 o = pc.sourceTexel * pc.offset;
    vec3 sum = texture(source, uv + vec2(-o.x * 2.0, 0.0)).rgb;
    sum += texture(source, uv + vec2(o.x * 2.0, 0.0)).rgb;
    sum += texture(source, uv + vec2(0.0, -o.y * 2.0)).rgb;
    sum += texture(source, uv + vec2(0.0, o.y * 2.0)).rgb;
    sum += texture(source, uv + vec2(-o.x, -o.y)).rgb * 2.0;
    sum += texture(source, uv + vec2(o.x, -o.y)).rgb * 2.0;
    sum += texture(source, uv + vec2(-o.x, o.y)).rgb * 2.0;
    sum += texture(source, uv + vec2(o.x, o.y)).rgb * 2.0;
    outColor = vec4(sum / 12.0, 1.0);
}
