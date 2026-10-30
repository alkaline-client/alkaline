// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-24.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// The sky planes at the far plane: z past w, clamped to depth 1.0 by the pipeline (see sky_far.vert). uv is (0,0)
// at the top-left with a positive-height viewport.
#extension GL_GOOGLE_include_directive : require
#include "sky_planes_common.glsl"
void main() {
    planesVertex(2.0);
}
