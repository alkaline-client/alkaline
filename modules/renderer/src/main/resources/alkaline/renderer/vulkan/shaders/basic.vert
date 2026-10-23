// SPDX-License-Identifier: CC0-1.0
// Original Alkaline bootstrap shader; authored for this renderer on 2026-10-11.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inColor;
layout(location = 0) out vec3 color;
layout(push_constant) uniform Transform {
    mat4 objectToClip;
} transform;
void main() {
    gl_Position = transform.objectToClip * vec4(inPosition, 1.0);
    color = inColor;
}
