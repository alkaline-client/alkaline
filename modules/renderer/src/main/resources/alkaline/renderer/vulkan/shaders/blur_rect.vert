// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One instanced rectangle of a blur pass's target, so a pass covers only the area frosted surfaces need;
// uv matches fullscreen.vert's (0,0 at the top left) inside it.
layout(location = 0) in vec4 inRect;  // left, top, right, bottom, normalized to the target
layout(location = 0) out vec2 uv;
void main() {
    vec2 corner = vec2((gl_VertexIndex & 2) != 0 ? 1.0 : 0.0, (gl_VertexIndex & 1) != 0 ? 1.0 : 0.0);
    uv = mix(inRect.xy, inRect.zw, corner);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
