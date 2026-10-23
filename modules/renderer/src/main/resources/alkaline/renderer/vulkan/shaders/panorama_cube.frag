// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Renders the 1.8.9 title panorama cube into a square target in one pass. Vanilla draws the cube 64 times
// with a sub-pixel camera jitter, blending tile i at alpha floor(255/(i+1))/255; this ray-casts each jittered
// camera per fragment and applies the same running blend without 8-bit intermediate rounding.
layout(set = 0, binding = 0) uniform sampler2DArray faces;
layout(push_constant) uniform Panorama {
    mat4 inverseOrbit;   // (Rx(pitch) * Ry(yaw))^-1, column-major
    int samplesPerAxis;  // vanilla: 8
    float jitterScale;   // vanilla: 1/64
    float tanHalfFov;    // vanilla: tan(60 degrees)
    float targetSize;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

vec3 sampleCube(vec3 origin, vec3 direction) {
    vec3 safe = mix(direction, vec3(1e-6), equal(direction, vec3(0.0)));
    vec3 exitT = (sign(safe) - origin) / safe;
    float t;
    vec2 local;
    float face;
    if (exitT.x <= exitT.y && exitT.x <= exitT.z) {
        vec3 hit = origin + direction * exitT.x;
        face = direction.x > 0.0 ? 1.0 : 3.0;
        local = direction.x > 0.0 ? vec2(-hit.z, hit.y) : vec2(hit.z, hit.y);
    } else if (exitT.y <= exitT.z) {
        vec3 hit = origin + direction * exitT.y;
        face = direction.y > 0.0 ? 5.0 : 4.0;
        local = direction.y > 0.0 ? vec2(hit.x, -hit.z) : vec2(hit.x, hit.z);
    } else {
        vec3 hit = origin + direction * exitT.z;
        face = direction.z > 0.0 ? 0.0 : 2.0;
        local = direction.z > 0.0 ? vec2(hit.x, hit.y) : vec2(-hit.x, hit.y);
    }
    return texture(faces, vec3(local * 0.5 + 0.5, face)).rgb;
}

void main() {
    // Work in GL window coordinates: +y up, matching the vanilla viewport the image was rendered into.
    vec2 ndc = vec2(gl_FragCoord.x, pc.targetSize - gl_FragCoord.y) / pc.targetSize * 2.0 - 1.0;
    vec3 eye = vec3(ndc * pc.tanHalfFov, -1.0);
    // Undo the fixed Rx(180) * Rz(90) camera basis: (x, y, z) -> (-y, -x, -z).
    vec3 basis = vec3(-eye.y, -eye.x, -eye.z);
    mat3 orbit = mat3(pc.inverseOrbit);
    vec3 direction = orbit * basis;
    int grid = max(pc.samplesPerAxis, 1);
    int count = grid * grid;
    vec3 color = vec3(0.0);
    for (int i = 0; i < count; i++) {
        vec2 jitter = (vec2(i % grid, i / grid) / float(grid) - 0.5) * pc.jitterScale;
        vec3 origin = -(orbit * vec3(jitter, 0.0));
        float alpha = float(255 / (i + 1)) / 255.0;
        color = mix(color, sampleCube(origin, direction), alpha);
    }
    outColor = vec4(color, 1.0);
}
