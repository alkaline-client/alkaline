// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Clouds fade into the fog color; alpha stays (fixed-function fog changes color only).
layout(push_constant) uniform Clouds {
    mat4 viewProjection;
    vec4 origin;
    vec4 color;
    vec4 fogColor;
    vec4 fog;
} pc;
layout(location = 0) in vec4 color;
layout(location = 1) in float fog;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(mix(color.rgb, pc.fogColor.rgb, fog), color.a);
}
