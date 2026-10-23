// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One layer of an enchantment glint (RenderItem.renderEffect, LayerArmorBase.renderGlint, or an OptiFine CIT
// enchantment): the glint texture times the layer color times the lightmap. Vanilla blends it as src * src + dst;
// custom layers use their pack blend. The pass normally runs on the UNORM alias of the color attachment, so the
// blend adds encoded values exactly as vanilla's framebuffer does. Without an alias (flag 1), the blend runs in
// linear space: a squared layer's output is chosen so it adds the linearized increment, which matches vanilla over
// black only; other layers output their linearized color. Vanilla's GL_EQUAL depth test keeps the glint off
// texels the model's alpha test dropped (an armor layer's transparent parts); here the glint repeats that alpha
// test on the model's own texture, which holds however the two passes round depth.
layout(set = 0, binding = 0) uniform sampler2D glintTexture;
layout(set = 1, binding = 0) uniform sampler2D modelTexture;
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    uvec2 instances;
    uvec2 matrices;
    vec4 matrix;
    vec4 scroll;
    vec4 tint;
} scene;
layout(location = 0) in vec2 pattern;
layout(location = 1) flat in vec3 light;
layout(location = 2) in vec2 uv;
layout(location = 3) flat in float alpha;
layout(location = 0) out vec4 outColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

void main() {
    if (texture(modelTexture, uv).a * alpha <= 0.1) {
        discard;
    }
    vec4 texel = texture(glintTexture, pattern);
    vec3 color = texel.rgb * scene.tint.rgb * light;
    uint flags = uint(scene.tint.w);
    if ((flags & 1u) != 0u) {
        color = (flags & 4u) != 0u ? sqrt(toLinear(color * color)) : toLinear(color);
    }
    outColor = vec4(color, texel.a);
}
