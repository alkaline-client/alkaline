// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One instanced rounded rectangle per glass region, positioned in pixels.
layout(push_constant) uniform Glass {
    vec2 screenSize;
    vec2 regionScale;  // UI pixels to framebuffer pixels
} pc;
layout(location = 0) in vec4 inRect;     // left, top, right, bottom (UI pixels)
layout(location = 1) in float inRadius;  // UI pixels
layout(location = 2) in vec4 inTint;     // straight alpha
layout(location = 3) in float inOpacity;
layout(location = 0) out vec2 pixel;
layout(location = 1) flat out vec4 rect;
layout(location = 2) flat out float radius;
layout(location = 3) flat out vec4 tint;
layout(location = 4) flat out float opacity;

void main() {
    vec4 scaled = inRect * pc.regionScale.xyxy;
    vec2 corner = vec2((gl_VertexIndex & 2) != 0 ? 1.0 : 0.0, (gl_VertexIndex & 1) != 0 ? 1.0 : 0.0);
    // Pad one pixel so the antialiased edge is not clipped.
    vec2 position = mix(scaled.xy - 1.0, scaled.zw + 1.0, corner);
    pixel = position;
    rect = scaled;
    radius = inRadius * min(pc.regionScale.x, pc.regionScale.y);
    tint = inTint;
    opacity = inOpacity;
    gl_Position = vec4(position / pc.screenSize * 2.0 - 1.0, 0.0, 1.0);
}
