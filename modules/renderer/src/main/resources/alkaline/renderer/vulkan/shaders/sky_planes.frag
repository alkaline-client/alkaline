// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-17.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Vanilla 1.8.9's sky planes (RenderGlobal.renderSky), traced per pixel in gamma space: the clear to the fog
// color, the sky plane 16 blocks above the eye, and (pass 1) the dark plane at sea level with the black void box
// under the eye when it is below sea level. Planes span +-extent blocks and are fogged by distance from the eye
// (linear 0..far, or exponential under water and lava), as setupFog(-1) fogs them.
layout(push_constant) uniform Planes {
    vec4 right;     // xyz: camera right scaled by tan(half horizontal fov)
    vec4 up;        // xyz: camera up scaled by tan(half vertical fov)
    vec4 forward;   // xyz: camera forward; w: 0 upper pass, 1 lower pass
    vec4 sky;       // rgb: sky color; w: plane extent (blocks)
    vec4 fog;       // rgb: fog color; w: fog end (linear) or 0
    vec4 lower;     // rgb: sea-level plane color; w: eye height above sea level (vanilla's horizon, 63)
    vec4 params;    // x: fog density (exponential when > 0); y: 1 to draw the sky plane (render distance >= 4)
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

float fogAmount(float distance) {
    if (pc.params.x > 0.0) {
        return 1.0 - exp(-pc.params.x * distance);
    }
    return clamp(distance / max(pc.fog.w, 1.0), 0.0, 1.0);
}

void main() {
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec3 direction = normalize(pc.forward.xyz + ndc.x * pc.right.xyz + ndc.y * pc.up.xyz);
    float extent = pc.sky.w;
    if (pc.forward.w < 0.5) {
        vec3 color = pc.fog.rgb;
        if (pc.params.y > 0.5 && direction.y > 0.0) {
            float t = 16.0 / direction.y;
            vec2 hit = direction.xz * t;
            if (abs(hit.x) <= extent && abs(hit.y) <= extent) {
                color = mix(pc.sky.rgb, pc.fog.rgb, fogAmount(t));
            }
        }
        outColor = vec4(color, 1.0);
        return;
    }
    float height = pc.lower.w;
    bool covered = false;
    vec3 color = vec3(0.0);
    if (height < 0.0 && direction.y < 0.0) {
        // The void box: a unit box around the eye from y = -1 down; rays leaving it below y = -1 are black.
        float side = 1.0 / max(max(abs(direction.x), abs(direction.z)), 1e-6);
        if (direction.y * side <= -1.0) {
            covered = true;
        }
    }
    float planeY = -height;
    if (direction.y != 0.0 && sign(direction.y) == sign(planeY)) {
        float t = planeY / direction.y;
        vec2 hit = direction.xz * t;
        if (abs(hit.x) <= extent && abs(hit.y) <= extent) {
            color = mix(pc.lower.rgb, pc.fog.rgb, fogAmount(t));
            covered = true;
        }
    }
    if (!covered) {
        discard;
    }
    outColor = vec4(color, 1.0);
}
