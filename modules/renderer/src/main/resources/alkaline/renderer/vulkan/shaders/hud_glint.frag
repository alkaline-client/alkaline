// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-23.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// The glint over a HUD item icon, drawn over the composited UI layer. Vanilla's GUI glint covers only the item's
// own pixels (depth equal), and later overlays (stack counts, durability bars, tooltips, a held cursor stack)
// cover it. Here a pixel glints where the UI layer still shows the icon's opaque texel unchanged, so anything the
// UI drew over the icon masks the glint without the UI knowing about it. Skia scales the icon with nearest
// sampling; the neighboring texels are accepted too, so a rounding difference at a texel edge leaves no seam.
// The pattern scrolls in the icon's sprite coordinates as vanilla's does in its block-atlas coordinates: the
// sprite spans 16 px of a 512x512 atlas, v running down.
layout(set = 0, binding = 0) uniform sampler2D uiLayer;
layout(set = 1, binding = 0) uniform sampler2D iconImage;
layout(set = 2, binding = 0) uniform sampler2D glintTexture;
layout(push_constant) uniform Icon {
    vec4 rect;
    vec4 frame;
    vec4 matrix;
    vec4 scroll;
    vec4 tint;
} icon;
layout(location = 0) out vec4 outColor;

const float SPRITE_SPAN = 16.0 / 512.0;
const float SAME = 1.5 / 255.0;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

bool shows(vec4 ui, ivec2 texel, ivec2 size) {
    vec4 color = texelFetch(iconImage, clamp(texel, ivec2(0), size - 1), 0);
    return color.a >= 1.0 && all(lessThan(abs(ui.rgb - color.rgb), vec3(SAME)));
}

void main() {
    vec2 local = (gl_FragCoord.xy - icon.rect.xy) / (icon.rect.zw - icon.rect.xy);
    ivec2 uiSize = textureSize(uiLayer, 0);
    vec4 ui = texelFetch(uiLayer, clamp(ivec2(gl_FragCoord.xy * icon.frame.zw), ivec2(0), uiSize - 1), 0);
    ivec2 size = textureSize(iconImage, 0);
    ivec2 texel = ivec2(floor(local * vec2(size)));
    if (ui.a < 1.0 || !(shows(ui, texel, size) || shows(ui, texel + ivec2(1, 0), size)
            || shows(ui, texel - ivec2(1, 0), size) || shows(ui, texel + ivec2(0, 1), size)
            || shows(ui, texel - ivec2(0, 1), size))) {
        discard;
    }
    vec2 pattern = mat2(icon.matrix.xy, icon.matrix.zw) * (local * SPRITE_SPAN + icon.scroll.xy);
    vec4 glint = texture(glintTexture, pattern);
    vec3 color = glint.rgb * icon.tint.rgb;
    uint flags = uint(icon.scroll.z);
    if ((flags & 1u) != 0u) {
        color = (flags & 4u) != 0u ? sqrt(toLinear(color * color)) : toLinear(color);
    }
    outColor = vec4(color, glint.a);
}
