// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// Bakes the Blue Planet once into one square map holding two equirectangular layers:
//   top half    rgb = surface albedo (linear), a = cloud coverage
//   bottom half r = ocean, g = elevation, b = night lights, a = ice
// Noise is evaluated on the unit sphere, so there is no seam at the date line and no pinching at the poles.
// Everything the scene shader needs per pixel is a texture read; nothing sparkles or aliases at runtime.
layout(push_constant) uniform Bake {
    float seed;
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;

float hash31(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973) + pc.seed);
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    return vec3(hash31(p), hash31(p + 17.3), hash31(p - 31.7));
}

float noise3(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    vec3 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1, 0, 0)), w.x),
                   mix(hash31(cell + vec3(0, 1, 0)), hash31(cell + vec3(1, 1, 0)), w.x), w.y),
               mix(mix(hash31(cell + vec3(0, 0, 1)), hash31(cell + vec3(1, 0, 1)), w.x),
                   mix(hash31(cell + vec3(0, 1, 1)), hash31(cell + vec3(1, 1, 1)), w.x), w.y), w.z);
}

float fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;
    float total = 0.0;
    for (int i = 0; i < octaves; i++) {
        sum += amplitude * noise3(p);
        total += amplitude;
        p = p * 2.03 + vec3(17.1, -9.3, 4.7);
        amplitude *= 0.5;
    }
    return sum / total;
}

float ridged(vec3 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < octaves; i++) {
        float n = 1.0 - abs(noise3(p) * 2.0 - 1.0);
        sum += amplitude * n * n;
        p = p * 2.11 + vec3(-5.3, 12.9, 3.1);
        amplitude *= 0.5;
    }
    return sum;
}

/** Distance to the nearest jittered feature point: small bright clusters for city lights. */
float cells(vec3 p) {
    vec3 cell = floor(p);
    float nearest = 8.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 c = cell + vec3(x, y, z);
                vec3 point = c + hash33(c);
                nearest = min(nearest, length(point - p));
            }
        }
    }
    return nearest;
}

/** Rotates p around axis by angle (Rodrigues). */
vec3 rotateAround(vec3 p, vec3 axis, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return p * c + cross(axis, p) * s + axis * dot(axis, p) * (1.0 - c);
}

/** A few cyclones: the domain twists around fixed centers, so the cloud field spirals there. */
vec3 cyclones(vec3 n) {
    vec3 centers[4] = vec3[](normalize(vec3(0.6, 0.45, 0.65)), normalize(vec3(-0.7, -0.5, 0.4)),
                             normalize(vec3(0.2, 0.62, -0.75)), normalize(vec3(-0.3, -0.35, -0.88)));
    float spin[4] = float[](1.0, -1.0, 1.0, -1.0);  // counter-clockwise north, clockwise south
    for (int i = 0; i < 4; i++) {
        float d = acos(clamp(dot(n, centers[i]), -1.0, 1.0));
        float twist = spin[i] * 5.0 * exp(-d * d * 60.0);
        n = rotateAround(n, centers[i], twist);
    }
    return n;
}

struct Terrain {
    float continent;
    float land;
    float elevation;
    float temperature;
    float moisture;
    float coast;
};

Terrain terrain(vec3 n) {
    vec3 warp = vec3(fbm(n * 1.5, 5), fbm(n * 1.5 + 5.2, 5), fbm(n * 1.5 - 3.1, 5)) - 0.5;
    float continent = fbm(n * 1.8 + warp * 1.5, 9);
    float land = smoothstep(0.515, 0.522, continent);
    float above = clamp((continent - 0.518) / 0.12, 0.0, 1.0);
    float mountains = ridged(n * 5.0 + warp * 2.0, 6) * smoothstep(0.53, 0.62, continent);
    float elevation = clamp(above * 0.55 + mountains * 0.55, 0.0, 1.0) * land;
    float latitude = abs(n.y);
    float temperature = 1.0 - latitude * 1.2 - elevation * 0.55 + (fbm(n * 3.0 + 11.0, 4) - 0.5) * 0.35;
    // Subtropical deserts near 25-35 degrees, wet tropics at the equator.
    float moisture = fbm(n * 2.4 + 7.3, 6) + 0.2 * exp(-pow(latitude / 0.15, 2.0))
            - 0.38 * exp(-pow((latitude - 0.45) / 0.12, 2.0));
    float coast = 1.0 - smoothstep(0.0, 0.04, abs(continent - 0.518));
    return Terrain(continent, land, elevation, temperature, moisture, coast);
}

vec3 surfaceColor(vec3 n, Terrain t, out float ice) {
    float depth = clamp((0.518 - t.continent) * 6.0, 0.0, 1.0);
    vec3 deep = vec3(0.003, 0.012, 0.042);
    vec3 shallow = vec3(0.010, 0.060, 0.090);
    vec3 ocean = mix(shallow, deep, smoothstep(0.0, 0.6, depth + (1.0 - t.coast) * 0.5));

    vec3 desert = vec3(0.34, 0.25, 0.14);
    vec3 steppe = vec3(0.16, 0.15, 0.06);
    vec3 forest = vec3(0.028, 0.064, 0.018);
    vec3 jungle = vec3(0.016, 0.042, 0.014);
    vec3 tundra = vec3(0.09, 0.095, 0.07);
    vec3 rock = vec3(0.13, 0.11, 0.09);
    float wet = smoothstep(0.38, 0.62, t.moisture);
    vec3 warm = mix(desert, mix(steppe, jungle, smoothstep(0.55, 0.75, t.moisture)), wet);
    vec3 cool = mix(steppe, forest, wet);
    vec3 ground = mix(mix(tundra, cool, smoothstep(0.15, 0.35, t.temperature)), warm,
                      smoothstep(0.45, 0.7, t.temperature));
    ground = mix(ground, rock, smoothstep(0.45, 0.8, t.elevation));
    ground *= 0.8 + 0.4 * fbm(n * 40.0, 4);

    float seaIce = smoothstep(0.86, 0.92, abs(n.y) + (fbm(n * 8.0, 3) - 0.5) * 0.08);
    float landIce = t.land * smoothstep(0.12, 0.02, t.temperature);
    ice = max(seaIce, landIce);
    vec3 color = mix(ocean, ground, t.land);
    return mix(color, vec3(0.80, 0.84, 0.88), ice);
}

float cloudCoverage(vec3 n) {
    vec3 swirled = cyclones(n);
    vec3 stretch = vec3(swirled.x, swirled.y * 2.0, swirled.z);
    vec3 warp = vec3(fbm(stretch * 2.0 + 3.0, 4), fbm(stretch * 2.0 - 7.0, 4), fbm(stretch * 2.0 + 13.0, 4)) - 0.5;
    float field = fbm(stretch * 2.6 + warp * 2.2, 8);
    float latitude = abs(n.y);
    float bands = 0.62 + 0.22 * exp(-pow(n.y / 0.10, 2.0)) + 0.22 * exp(-pow((latitude - 0.72) / 0.14, 2.0))
            - 0.18 * exp(-pow((latitude - 0.42) / 0.10, 2.0));
    float detail = fbm(swirled * 22.0, 4);
    float coverage = smoothstep(0.42, 0.72, field * bands + (detail - 0.5) * 0.18 + 0.12);
    return coverage * coverage * (3.0 - 2.0 * coverage);
}

void main() {
    bool aux = uv.y >= 0.5;
    float v = fract(uv.y * 2.0);
    float longitude = (uv.x - 0.5) * 2.0 * PI;
    float latitude = (0.5 - v) * PI;
    vec3 n = vec3(cos(latitude) * sin(longitude), sin(latitude), cos(latitude) * cos(longitude));
    Terrain t = terrain(n);
    if (!aux) {
        float ice;
        vec3 albedo = surfaceColor(n, t, ice);
        outColor = vec4(albedo, cloudCoverage(n));
    } else {
        float ice;
        surfaceColor(n, t, ice);
        // Night lights: clusters of cities, densest along temperate, wet coasts; none on ice or deep desert.
        float habitable = t.land * (1.0 - ice) * smoothstep(0.25, 0.55, t.temperature) * smoothstep(0.3, 0.5, t.moisture)
                * (1.0 - smoothstep(0.5, 0.8, t.elevation));
        float population = habitable * (0.35 + 0.65 * t.coast) * smoothstep(0.35, 0.7, fbm(n * 9.0 + 2.0, 4));
        float cities = (1.0 - smoothstep(0.0, 0.35, cells(n * 140.0))) * 0.8
                + (1.0 - smoothstep(0.0, 0.25, cells(n * 420.0))) * 0.5;
        float lights = clamp(population * cities * 1.6, 0.0, 1.0);
        outColor = vec4(1.0 - t.land, t.elevation, lights, ice);
    }
}
