// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Cloud cells from a static 16x16-cell chunk mesh: each vertex word is a corner within the chunk (x, z), the top
// bit and the face; the chunk's place relative to the eye and the cell size come per draw.
layout(push_constant) uniform Clouds {
    mat4 viewProjection;
    vec4 origin;    // xyz: the chunk's first corner relative to the eye, in blocks; w: cell size
    vec4 color;     // rgb: cloud color; a: vanilla's 0.8
    vec4 fogColor;  // rgb: fog color; w: fog start
    vec4 fog;       // x: fog end; y: 1 when fogged; z: exponential density or 0; w: cloud thickness (0 for Fast)
} pc;
layout(location = 0) in uint word;
layout(location = 0) out vec4 color;
layout(location = 1) out float fog;

void main() {
    float x = float(word & 31u);
    float z = float((word >> 5) & 31u);
    float top = float((word >> 10) & 1u);
    uint face = (word >> 11) & 7u;
    vec3 relative = pc.origin.xyz + vec3(x * pc.origin.w, top * pc.fog.w, z * pc.origin.w);
    gl_Position = pc.viewProjection * vec4(relative, 1.0);
    // RenderGlobal.renderCloudsFancy's face shading; Fast clouds are flat.
    float shade = pc.fog.w == 0.0 ? 1.0 : face == 0u ? 0.7 : face == 1u ? 1.0 : face < 4u ? 0.9 : 0.8;
    color = vec4(pc.color.rgb * shade, pc.color.a);
    float distance = length(relative);
    fog = pc.fog.y < 0.5 ? 0.0 : pc.fog.z > 0.0 ? 1.0 - exp(-pc.fog.z * distance)
            : clamp((distance - pc.fogColor.w) / max(pc.fog.x - pc.fogColor.w, 1.0), 0.0, 1.0);
}
