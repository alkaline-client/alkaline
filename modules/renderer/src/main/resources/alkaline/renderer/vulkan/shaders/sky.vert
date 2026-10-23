// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Sky and weather geometry around the eye (positions relative to it), with a texture coordinate and an 8-bit
// color, both used as gamma-space values like vanilla's fixed-function pipeline.
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
    uv = inUv;
    color = inColor * pc.tint;
}
