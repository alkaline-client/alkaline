// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Frosted backdrop: the blurred scene inside an antialiased rounded rectangle, lifted slightly and tinted.
layout(set = 0, binding = 0) uniform sampler2D blurred;
layout(push_constant) uniform Glass {
    vec2 screenSize;
    vec2 regionScale;
} pc;
layout(location = 0) in vec2 pixel;
layout(location = 1) flat in vec4 rect;
layout(location = 2) flat in float radius;
layout(location = 3) flat in vec4 tint;
layout(location = 4) flat in float opacity;
layout(location = 0) out vec4 outColor;

float roundedRectDistance(vec2 p, vec4 r, float cornerRadius) {
    vec2 center = (r.xy + r.zw) * 0.5;
    vec2 halfSize = (r.zw - r.xy) * 0.5;
    float clamped = min(cornerRadius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p - center) - halfSize + clamped;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - clamped;
}

void main() {
    float coverage = clamp(0.5 - roundedRectDistance(pixel, rect, radius), 0.0, 1.0);
    if (coverage <= 0.0) {
        discard;
    }
    vec3 backdrop = texture(blurred, gl_FragCoord.xy / pc.screenSize).rgb;
    // Gentle saturation boost keeps blurred color from looking muddy.
    float luma = dot(backdrop, vec3(0.2126, 0.7152, 0.0722));
    backdrop = mix(vec3(luma), backdrop, 1.15);
    backdrop = mix(backdrop, tint.rgb, tint.a);
    outColor = vec4(backdrop, coverage * opacity);
}
