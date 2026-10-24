// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16 (as entity.*), renamed 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
#extension GL_EXT_buffer_reference : require
// Instanced models (posed cuboids and rigid items). Static vertices carry a part index; each instance points at its block of 3x4
// part matrices, so one draw renders every instance of a model/texture pair with no per-frame vertex work.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Instances { uvec4 records[]; };
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Matrices { vec4 rows[]; };
// The terrain's fog: color.rgb (linear) and start; then end, enabled, exponential density.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Fog { vec4 color; vec4 params; };
layout(push_constant) uniform Scene {
    mat4 viewProjection;
    Instances instances;
    Matrices matrices;
    vec4 light0;   // xyz: direction toward light 0; w: 1 when output must be linearized
    vec4 light1;   // xyz: direction toward light 1; w: ambient
    Fog fog;
} scene;
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inNormal;
layout(location = 3) in uint inPart;
layout(location = 0) out vec2 uv;
layout(location = 1) out vec4 tint;
layout(location = 2) out vec4 overlay;
layout(location = 3) out vec3 shade;
layout(location = 4) out float fog;

void main() {
    // record: x = first matrix row (vec4 index), y = tint RGBA8, z = overlay RGBA8, w = light RGBA8
    uvec4 record = scene.instances.records[gl_InstanceIndex];
    uint base = record.x + inPart * 3u;
    vec4 r0 = scene.matrices.rows[base];
    vec4 r1 = scene.matrices.rows[base + 1u];
    vec4 r2 = scene.matrices.rows[base + 2u];
    vec4 local = vec4(inPosition, 1.0);
    vec3 world = vec3(dot(r0, local), dot(r1, local), dot(r2, local));
    gl_Position = scene.viewProjection * vec4(world, 1.0);
    // Instances are camera-relative, so the distance is the length of the position (radial fog, as terrain).
    vec4 fogParams = scene.fog.params;
    float distance = length(world);
    fog = fogParams.y < 0.5 ? 0.0 : fogParams.z > 0.0 ? 1.0 - exp(-fogParams.z * distance)
            : clamp((distance - scene.fog.color.w) / max(fogParams.x - scene.fog.color.w, 1.0), 0.0, 1.0);

    vec3 normal = vec3(dot(r0.xyz, inNormal.xyz), dot(r1.xyz, inNormal.xyz), dot(r2.xyz, inNormal.xyz));
    float length2 = dot(normal, normal);
    normal = length2 > 0.0 ? normal * inversesqrt(length2) : vec3(0.0);
    // Fixed-function GL lighting as configured by RenderHelper: two directional lights at 0.6, ambient 0.4.
    float diffuse = scene.light1.w
            + 0.6 * (max(dot(normal, scene.light0.xyz), 0.0) + max(dot(normal, scene.light1.xyz), 0.0));
    vec4 light = unpackUnorm4x8(record.w);
    // A zero light alpha marks an unlit instance (lightning, flashes): its color is drawn flat.
    shade = (light.a < 0.5 ? vec3(1.0) : min(vec3(diffuse), vec3(1.0))) * light.rgb;
    uv = inUv;
    tint = unpackUnorm4x8(record.y);
    overlay = unpackUnorm4x8(record.z);
}
