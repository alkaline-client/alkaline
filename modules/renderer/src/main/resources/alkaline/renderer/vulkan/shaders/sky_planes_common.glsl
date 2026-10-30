// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-30.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
// The sky planes' screen coverage (sky_planes.frag has the push block's meaning). The upper pass covers the
// viewport with one triangle. The lower pass, with the eye above sea level, can only cover what lies below the
// horizon: the screen is clipped to that half-plane (direction.y < 0 is linear in uv), and the polygon, up to five
// corners, goes out as a fan of three triangles. Its fragments then skip the test that would discard the rest.
layout(push_constant) uniform Planes {
    vec4 right;
    vec4 up;
    vec4 forward;
    vec4 sky;
    vec4 fog;
    vec4 lower;
    vec4 params;
} pc;
layout(location = 0) out vec2 uv;

/** The unnormalized view direction's y at a uv (0,0 top left), as sky_planes.frag builds the direction. */
float rise(vec2 point) {
    return pc.forward.y + (point.x * 2.0 - 1.0) * pc.right.y + (1.0 - point.y * 2.0) * pc.up.y;
}

void planesVertex(float z) {
    if (pc.forward.w < 0.5 || pc.lower.w <= 0.0) {
        vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        // Vertices past the first triangle collapse (the lower pass draws nine either way).
        uv = gl_VertexIndex < 3 ? corner : vec2(0.0);
        gl_Position = vec4(uv * 2.0 - 1.0, z, 1.0);
        return;
    }
    const vec2 corners[4] = vec2[4](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
    // A little past the horizon, so rounding never clips a pixel the fragment shader would draw.
    const float margin = 1e-4;
    vec2 polygon[5];
    int count = 0;
    for (int index = 0; index < 4; index++) {
        vec2 a = corners[index];
        vec2 b = corners[(index + 1) & 3];
        float da = margin - rise(a);
        float db = margin - rise(b);
        if (da >= 0.0) {
            polygon[count++] = a;
        }
        if ((da >= 0.0) != (db >= 0.0)) {
            polygon[count++] = mix(a, b, da / (da - db));
        }
    }
    int triangle = gl_VertexIndex / 3;
    int corner = gl_VertexIndex % 3;
    int vertex = corner == 0 ? 0 : triangle + corner;
    uv = count >= 3 && vertex < count ? polygon[vertex] : count >= 3 ? polygon[0] : vec2(0.0);
    gl_Position = vec4(uv * 2.0 - 1.0, z, 1.0);
}
