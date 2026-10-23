// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Expands one per-instance record into a camera-facing quad (4-vertex strip). Positions are camera-relative.
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    vec4 right;   // xyz: camera right in world space
    vec4 up;      // xyz: camera up; w: 1 when output must be linearized
} scene;
layout(location = 0) in vec3 inCenter;
layout(location = 1) in float inHalfSize;
layout(location = 2) in vec2 inUvMin;
layout(location = 3) in vec2 inUvMax;
layout(location = 4) in vec4 inColor;
layout(location = 5) in uint inLayer;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 color;
layout(location = 2) flat out uint layer;

void main() {
    vec2 corner = vec2((gl_VertexIndex & 2) != 0 ? 1.0 : -1.0, (gl_VertexIndex & 1) != 0 ? 1.0 : -1.0);
    vec3 world = inCenter + (corner.x * scene.right.xyz + corner.y * scene.up.xyz) * inHalfSize;
    gl_Position = scene.viewProjection * vec4(world, 1.0);
    uv = vec2(corner.x > 0.0 ? inUvMax.x : inUvMin.x, corner.y > 0.0 ? inUvMin.y : inUvMax.y);
    color = inColor;
    layer = inLayer;
}
