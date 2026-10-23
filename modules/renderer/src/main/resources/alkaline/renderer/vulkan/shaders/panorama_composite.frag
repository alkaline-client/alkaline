// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Stretches the blurred square across the screen with vanilla's transposed, aspect-preserving mapping and
// applies the two title-screen gradients. All math runs on sRGB-encoded values like the GL original; the
// result is linearized only when the attachment re-encodes to sRGB.
layout(set = 0, binding = 0) uniform sampler2D blurred;
layout(push_constant) uniform Composite {
    vec4 overlayTopA;
    vec4 overlayBottomA;
    vec4 overlayTopB;
    vec4 overlayBottomB;
    vec2 halfExtent;   // (height, width) * (120 / max(width, height)) / 256
    int linearOutput;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    float u = 0.5 + pc.halfExtent.x * (1.0 - 2.0 * uv.y);
    float v = 0.5 + pc.halfExtent.y * (1.0 - 2.0 * uv.x);
    vec3 color = texture(blurred, vec2(u, 1.0 - v)).rgb;
    vec4 a = mix(pc.overlayTopA, pc.overlayBottomA, uv.y);
    color = mix(color, a.rgb, a.a);
    vec4 b = mix(pc.overlayTopB, pc.overlayBottomB, uv.y);
    color = mix(color, b.rgb, b.a);
    outColor = vec4(pc.linearOutput != 0 ? toLinear(color) : color, 1.0);
}
