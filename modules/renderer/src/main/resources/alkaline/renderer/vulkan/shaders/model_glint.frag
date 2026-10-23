// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One layer of vanilla's enchantment glint (RenderItem.renderEffect): the glint texture times the vertex color
// 0x8040CC times the lightmap, blended as src * src + dst. It is drawn twice per item, once per layer, like
// vanilla. The pass normally runs on the UNORM alias of the color attachment, so the blend adds encoded values
// exactly as vanilla's framebuffer does. Without an alias (w = 1), the blend runs in linear space: the output is
// chosen so it adds the linearized increment, which matches vanilla over black only.
layout(set = 0, binding = 0) uniform sampler2D glintTexture;
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    uvec2 instances;
    uvec2 matrices;
    vec4 glint;
} scene;
layout(location = 0) in vec2 pattern;
layout(location = 1) flat in vec3 light;
layout(location = 0) out vec4 outColor;

const vec3 TINT = vec3(128.0, 64.0, 204.0) / 255.0;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    vec3 color = texture(glintTexture, pattern).rgb * TINT * light;
    outColor = vec4(scene.glint.w != 0.0 ? sqrt(toLinear(color * color)) : color, 1.0);
}
