// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Streamed textured quads in camera-relative world space: name tags and the world border wall.
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    vec4 flags;   // x: 1 when output must be linearized; y: alpha cutoff
} scene;
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 color;
void main() {
    gl_Position = scene.viewProjection * vec4(inPosition, 1.0);
    uv = inUv;
    color = inColor;
}
