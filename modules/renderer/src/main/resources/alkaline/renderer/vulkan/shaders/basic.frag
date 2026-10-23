// SPDX-License-Identifier: CC0-1.0
// Original Alkaline bootstrap shader; authored for this renderer on 2026-10-11.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
layout(location = 0) in vec3 color;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = vec4(color, 1.0);
}
