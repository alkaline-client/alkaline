// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// GuiIngame.renderVignette: the vignette texture tinted by the push color, subtracted from the scene by the burn blend
// (destination times one minus source), as vanilla's (ZERO, ONE_MINUS_SRC_COLOR).
layout(set = 0, binding = 0) uniform sampler2D vignette;
layout(push_constant) uniform Tint {
    vec4 color;
} tint;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = vec4(texture(vignette, uv).rgb * tint.color.rgb, 1.0);
}
