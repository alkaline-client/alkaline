// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-23.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One HUD item icon's glint layer: a quad over the icon's rectangle, in framebuffer pixels (y down).
layout(push_constant) uniform Icon {
    vec4 rect;     // left, top, right, bottom in framebuffer pixels
    vec4 frame;    // xy: framebuffer size; zw: UI layer pixels per framebuffer pixel
    vec4 matrix;   // the texture matrix's 2x2 part, column major
    vec4 scroll;   // xy: offset at this time; z: flags (1 linear fallback, 4 squared blend)
    vec4 tint;     // rgb: layer color
} icon;

void main() {
    vec2 corner = vec2((gl_VertexIndex & 2) != 0 ? 1.0 : 0.0, (gl_VertexIndex & 1) != 0 ? 1.0 : 0.0);
    vec2 pixel = mix(icon.rect.xy, icon.rect.zw, corner);
    gl_Position = vec4(pixel / icon.frame.xy * 2.0 - 1.0, 0.0, 1.0);
}
