// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-30.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// The sky planes drawn before everything else, without depth (see sky_planes_common.glsl).
#extension GL_GOOGLE_include_directive : require
#include "sky_planes_common.glsl"
void main() {
    planesVertex(0.0);
}
