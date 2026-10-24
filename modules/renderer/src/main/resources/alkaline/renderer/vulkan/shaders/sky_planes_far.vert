// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// fullscreen.vert at the far plane for the sky planes: z past w, clamped to depth 1.0 by the pipeline (see
// sky_far.vert). uv is (0,0) at the top-left with a positive-height viewport.
layout(location = 0) out vec2 uv;
void main() {
    vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 2.0, 1.0);
}
