// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// One step of the "Fluid" menu background's stable-fluids solver, as fragment passes over one packed state
// texture: rg = velocity (uv units per second, y down), b = dye, a = pressure. A step is: advect (mode 1),
// Jacobi pressure iterations (mode 2), then projection with dye advection (mode 3). Mode 0 seeds the field.
// The image border is a free-slip wall: neighbor reads clamp to the edge.
layout(set = 0, binding = 0) uniform sampler2D state;
layout(push_constant) uniform Step {
    vec2 texel;         // 1 / simulation size
    vec2 pointer;       // uv of the pointer
    vec2 pointerDelta;  // pointer movement since the previous step, uv
    float dt;           // seconds per step
    float time;         // seconds
    float aspect;       // width / height
    int mode;
    float stir;         // 1 when the pointer moved this step
    float pad;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outState;

// Uniform flow to the right; the field relaxes toward it so the scene never settles into stillness.
vec2 baseFlow() {
    return vec2(0.32 / pc.aspect, 0.0);
}

vec4 at(ivec2 cell) {
    return texelFetch(state, clamp(cell, ivec2(0), textureSize(state, 0) - 1), 0);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    if (pc.mode == 0) {
        float stripe = pow(max(0.0, cos(uv.y * 65.0)), 10.0);
        float dye = stripe * (1.0 - smoothstep(0.015, 0.06, uv.x)) * 0.8;
        outState = vec4(baseFlow(), dye, 0.0);
        return;
    }
    vec4 center = at(cell);
    if (pc.mode == 1) {
        // Semi-Lagrangian self-advection, a gentle pull toward the base flow, and the pointer's push.
        vec2 velocity = texture(state, uv - center.xy * pc.dt * 0.85).xy;
        velocity = mix(velocity, baseFlow(), 0.12);
        if (pc.stir > 0.5) {
            vec2 delta = (uv - pc.pointer) * vec2(pc.aspect, 1.0);
            velocity += pc.pointerDelta * exp(-dot(delta, delta) * 210.0) * 12.0;
        }
        outState = vec4(velocity * 0.999, center.b, center.a);
        return;
    }
    vec4 left = at(cell - ivec2(1, 0));
    vec4 right = at(cell + ivec2(1, 0));
    vec4 up = at(cell - ivec2(0, 1));
    vec4 down = at(cell + ivec2(0, 1));
    if (pc.mode == 2) {
        float divergence = 0.5 * ((right.x - left.x) + (down.y - up.y));
        outState = vec4(center.rgb, (left.a + right.a + up.a + down.a - divergence) * 0.25);
        return;
    }
    // Projection makes the velocity divergence-free; the dye then rides the projected flow.
    vec2 velocity = center.xy - 0.5 * vec2(right.a - left.a, down.a - up.a);
    float dye = texture(state, uv - velocity * pc.dt).b * 0.996;
    if (pc.stir > 0.5) {
        vec2 delta = (uv - pc.pointer) * vec2(pc.aspect, 1.0);
        dye += exp(-dot(delta, delta) * 330.0) * length(pc.pointerDelta) * 3.5;
    }
    // Streams keep entering from the left edge, swaying slowly.
    float incoming = pow(max(0.0, cos(uv.y * 45.0 + sin(pc.time * 0.34) * 0.3)), 10.0);
    dye = max(dye, incoming * (1.0 - smoothstep(0.0, 0.045, uv.x)) * 2.5);
    outState = vec4(velocity, clamp(dye, 0.0, 1.0), center.a);
}
