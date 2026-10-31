// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-30.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Draws a sampled image across the viewport at a uniform opacity, for cross-fading one scene into another.
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Fade {
    float opacity;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = vec4(texture(source, uv).rgb, pc.opacity);
}
