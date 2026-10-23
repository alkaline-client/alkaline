// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
// Enchantment glint over instanced models: the same transform as model.vert, so depth matches exactly.
// Vanilla scrolls the glint in the item's block-atlas texture coordinates. Alkaline's item geometry samples texel
// centers (constant per quad), so the atlas coordinates are rebuilt from the model's x/y: the sprite spans
// glint.z of the atlas (16 px of vanilla's 512x512 atlas: 1/32), with v running down the sprite.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Instances { uvec4 records[]; };
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Matrices { vec4 rows[]; };
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    Instances instances;
    Matrices matrices;
    vec4 glint;   // x: scroll; y: rotation (radians); z: sprite span in atlas units; w: 1 for the linear fallback
} scene;
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inNormal;
layout(location = 3) in uint inPart;
layout(location = 0) out vec2 pattern;
layout(location = 1) flat out vec3 light;

void main() {
    // record: x = first matrix row, w = light RGBA8 (the lightmap texel vanilla modulates the glint with)
    uvec4 record = scene.instances.records[gl_InstanceIndex];
    uint base = record.x + inPart * 3u;
    vec4 local = vec4(inPosition, 1.0);
    vec3 world = vec3(dot(scene.matrices.rows[base], local), dot(scene.matrices.rows[base + 1u], local),
            dot(scene.matrices.rows[base + 2u], local));
    gl_Position = scene.viewProjection * vec4(world, 1.0);
    vec2 atlas = vec2(inPosition.x, 1.0 - inPosition.y) * scene.glint.z;
    // Vanilla's texture matrix: scale(8) * translate(scroll, 0) * rotate(angle) applied to the atlas coordinates.
    float c = cos(scene.glint.y);
    float s = sin(scene.glint.y);
    pattern = 8.0 * (mat2(c, s, -s, c) * atlas + vec2(scene.glint.x, 0.0));
    light = unpackUnorm4x8(record.w).rgb;
}
