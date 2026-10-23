// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Collapses vanilla's repeated "copy viewport, redraw it transposed with three 1/256-offset taps" passes into a
// single separable convolution. Two passes cancel the transpose and form a 3x3 box. Vanilla's raw cube image
// is itself transposed, so its default odd pass count ends upright; this pass always outputs the upright image
// and only takes the per-axis kernels from the closed form. Addressing wraps like the GL_REPEAT copy texture.
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Blur {
    int size;
    int unused;
    int columnTaps;
    int rowTaps;
    vec4 weights[6];  // column weights first, then row weights, packed four per vec4
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

float weight(int index) {
    return pc.weights[index >> 2][index & 3];
}

vec3 fetchGl(int column, int row) {
    int size = pc.size;
    column = ((column % size) + size) % size;
    row = ((row % size) + size) % size;
    return texelFetch(source, ivec2(column, size - 1 - row), 0).rgb;
}

void main() {
    int x = int(gl_FragCoord.x);
    int y = pc.size - 1 - int(gl_FragCoord.y);
    int columnRadius = pc.columnTaps / 2;
    int rowRadius = pc.rowTaps / 2;
    vec3 sum = vec3(0.0);
    for (int a = 0; a < pc.columnTaps; a++) {
        float wa = weight(a);
        for (int b = 0; b < pc.rowTaps; b++) {
            sum += wa * weight(pc.columnTaps + b) * fetchGl(x + a - columnRadius, y + b - rowRadius);
        }
    }
    outColor = vec4(sum, 1.0);
}
