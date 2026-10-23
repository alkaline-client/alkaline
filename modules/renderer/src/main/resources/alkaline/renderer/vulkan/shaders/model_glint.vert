// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
// Enchantment glint over instanced models: the same transform as model.vert, so depth matches exactly.
// Vanilla scrolls an item's glint in the item's block-atlas texture coordinates. Alkaline's item geometry samples
// texel centers (constant per quad), so the atlas coordinates are rebuilt from the model's x/y: the sprite spans
// 16 px of vanilla's 512x512 atlas (1/32), with v running down the sprite. Armor scrolls in the model's own uv.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Instances { uvec4 records[]; };
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Matrices { vec4 rows[]; };
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    Instances instances;
    Matrices matrices;
    vec4 matrix;  // the texture matrix's 2x2 part, column major
    vec4 scroll;  // xy: offset at this time; zw: offset per unit of the instance's time
    vec4 tint;    // rgb: layer color; w: flags (1 linear fallback, 2 model uv, 4 squared blend)
} scene;
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inNormal;
layout(location = 3) in uint inPart;
layout(location = 0) out vec2 pattern;
layout(location = 1) flat out vec3 light;
layout(location = 2) out vec2 uv;
layout(location = 3) flat out float alpha;

const float SPRITE_SPAN = 16.0 / 512.0;

void main() {
    // record: x = first matrix row, y = instance time (float bits), z = the model's tint RGBA8 (for its alpha
    // test), w = light RGBA8 (the lightmap texel vanilla modulates the glint with)
    uvec4 record = scene.instances.records[gl_InstanceIndex];
    uint base = record.x + inPart * 3u;
    vec4 local = vec4(inPosition, 1.0);
    vec3 world = vec3(dot(scene.matrices.rows[base], local), dot(scene.matrices.rows[base + 1u], local),
            dot(scene.matrices.rows[base + 2u], local));
    gl_Position = scene.viewProjection * vec4(world, 1.0);
    uint flags = uint(scene.tint.w);
    vec2 coordinate = (flags & 2u) != 0u ? inUv : vec2(inPosition.x, 1.0 - inPosition.y) * SPRITE_SPAN;
    vec2 offset = scene.scroll.xy + uintBitsToFloat(record.y) * scene.scroll.zw;
    pattern = mat2(scene.matrix.xy, scene.matrix.zw) * (coordinate + offset);
    light = unpackUnorm4x8(record.w).rgb;
    uv = inUv;
    alpha = unpackUnorm4x8(record.z).a;
}
