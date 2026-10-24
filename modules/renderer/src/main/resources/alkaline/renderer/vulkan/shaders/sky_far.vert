// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// sky.vert for the sky itself (custom layers, sunrise, sun, moon, stars) at the far plane: z is pushed past w and
// the pipeline clamps depth, so every fragment lands at exactly 1.0. Depth-tested against terrain drawn first, the
// sky then shades only the pixels nothing covers, and looks as it does drawn before the terrain.
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    vec4 tint;  // multiplies the vertex color (the stars' brightness; 1 elsewhere)
} pc;
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 color;

void main() {
    gl_Position = pc.viewProjection * vec4(inPosition, 1.0);
    gl_Position.z = gl_Position.w * 2.0;
    uv = inUv;
    color = inColor * pc.tint;
}
