// SPDX-License-Identifier: CC0-1.0
// Original Alkaline shader; authored for this renderer on 2026-10-16.
// Dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
#version 450
// "Blue Planet": deep space, a ray-traced Earth, and a satellite in the foreground.
//  - Earth: baked surface/cloud/aux map (blue_planet_bake.frag), relief lighting from the baked elevation,
//    cloud shadows, ocean sun glint, baked city lights on the night side, and an 8-sample single-scattering
//    atmosphere (Rayleigh + Mie) that gives the blue limb and the orange terminator.
//  - Satellite: an analytic model (foil-wrapped bus, two four-panel solar wings, a dish, antennas), lit by the
//    sun and by earthshine, eclipsed by the planet, and supersampled 4x at its edges.
layout(set = 0, binding = 0) uniform sampler2D planetMap;  // top: albedo + clouds; bottom: ocean, elevation, lights, ice
layout(push_constant) uniform Scene {
    mat4 satellite;  // columns 0-2: local-to-view rotation; column 3: xyz position, w scale
    vec4 sun;        // xyz: direction toward the sun (view space); w: time in seconds
    vec4 earth;      // xyz: center (view space); w: radius
    vec4 view;       // x: aspect, y: tan(half vertical fov), z: earth spin, w: cloud drift (radians)
    vec4 extra;      // x: satellite bounding radius in local units, y: exposure, z: pixel footprint, w: wing angle
} pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;
// The spin axis leans away from the camera, so the visible cap is temperate latitudes rather than the pole.
const vec3 AXIS = normalize(vec3(0.28, 0.70, -0.66));
const float HALF_TEXEL = 0.5 / 2048.0;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash31(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    vec3 w = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1, 0, 0)), w.x),
                   mix(hash31(cell + vec3(0, 1, 0)), hash31(cell + vec3(1, 1, 0)), w.x), w.y),
               mix(mix(hash31(cell + vec3(0, 0, 1)), hash31(cell + vec3(1, 0, 1)), w.x),
                   mix(hash31(cell + vec3(0, 1, 1)), hash31(cell + vec3(1, 1, 1)), w.x), w.y), w.z);
}

vec2 sphereHit(vec3 origin, vec3 direction, vec3 center, float radius) {
    vec3 oc = origin - center;
    float b = dot(oc, direction);
    float c = dot(oc, oc) - radius * radius;
    float h = b * b - c;
    if (h < 0.0) {
        return vec2(-1.0);
    }
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

mat3 rotation(vec3 axis, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    vec3 a = axis * (1.0 - c);
    return mat3(
        a.x * axis + vec3(c, axis.z * s, -axis.y * s),
        a.y * axis + vec3(-axis.z * s, c, axis.x * s),
        a.z * axis + vec3(axis.y * s, -axis.x * s, c));
}

/** View direction to planet-fixed coordinates: tilt the axis onto +Y, then undo the spin around it. */
vec3 toPlanet(vec3 v, float spin) {
    vec3 x = normalize(cross(AXIS, vec3(0.0, 0.0, 1.0)));
    vec3 z = cross(x, AXIS);
    vec3 tilted = vec3(dot(v, x), dot(v, AXIS), dot(v, z));
    float c = cos(spin);
    float s = sin(spin);
    return vec3(c * tilted.x - s * tilted.z, tilted.y, s * tilted.x + c * tilted.z);
}

vec3 fromPlanet(vec3 l, float spin) {
    float c = cos(spin);
    float s = sin(spin);
    vec3 tilted = vec3(c * l.x + s * l.z, l.y, -s * l.x + c * l.z);
    vec3 x = normalize(cross(AXIS, vec3(0.0, 0.0, 1.0)));
    vec3 z = cross(x, AXIS);
    return x * tilted.x + AXIS * tilted.y + z * tilted.z;
}

vec2 equirect(vec3 n) {
    return vec2(atan(n.x, n.z) / (2.0 * PI) + 0.5, 0.5 - asin(clamp(n.y, -1.0, 1.0)) / PI);
}

vec4 surfaceLayer(vec2 e) {
    return texture(planetMap, vec2(e.x, clamp(e.y * 0.5, HALF_TEXEL, 0.5 - HALF_TEXEL)));
}

vec4 auxLayer(vec2 e) {
    return texture(planetMap, vec2(e.x, 0.5 + clamp(e.y * 0.5, HALF_TEXEL, 0.5 - HALF_TEXEL)));
}

// ---- space ------------------------------------------------------------------------------------------------

vec3 starfield(vec3 direction, float time) {
    vec3 color = vec3(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 520.0 : 1200.0;
        vec2 grid = equirect(direction) * vec2(scale * 2.0, scale);
        vec2 cell = floor(grid);
        float present = hash21(cell + float(layer) * 91.7);
        if (present > (layer == 0 ? 0.982 : 0.972)) {
            vec2 center = cell + 0.25 + 0.5 * vec2(hash21(cell + 3.1), hash21(cell + 7.7));
            float distanceToStar = length(grid - center);
            float brightness = pow(hash21(cell + 11.3), 7.0) * (layer == 0 ? 2.2 : 0.5) + 0.025;
            float twinkle = 0.8 + 0.2 * sin(time * (0.7 + 2.0 * hash21(cell)) + hash21(cell + 5.0) * 6.28);
            vec3 tint = mix(vec3(0.68, 0.78, 1.0), vec3(1.0, 0.86, 0.72), hash21(cell + 2.0));
            color += tint * brightness * twinkle * exp(-distanceToStar * distanceToStar * 40.0);
        }
    }
    // A faint band of the galaxy with mottled dust.
    vec3 bandNormal = normalize(vec3(0.35, 0.9, 0.25));
    float band = exp(-pow(dot(direction, bandNormal) * 3.8, 2.0));
    float dust = noise3(direction * 9.0) * 0.6 + noise3(direction * 23.0) * 0.4;
    color += vec3(0.012, 0.013, 0.022) * band * (0.5 + dust);
    return color;
}

// ---- atmosphere -------------------------------------------------------------------------------------------

struct Atmosphere {
    vec3 inscatter;
    vec3 transmittance;
};

Atmosphere atmosphere(vec3 rayDirection, float start, float end, vec3 center, float radius, vec3 sunDirection) {
    float top = radius * 1.028;
    float thickness = top - radius;
    float hr = thickness * 0.22;
    float hm = thickness * 0.08;
    vec3 betaR = vec3(0.030, 0.072, 0.17) / hr;
    float betaM = 0.02 / hm;
    float step = (end - start) / 8.0;
    vec3 sumR = vec3(0.0);
    vec3 sumM = vec3(0.0);
    float depthR = 0.0;
    float depthM = 0.0;
    for (int i = 0; i < 8; i++) {
        vec3 p = rayDirection * (start + step * (float(i) + 0.5));
        vec3 up = p - center;
        float altitude = max(length(up) - radius, 0.0);
        up = normalize(up);
        float dR = exp(-altitude / hr) * step;
        float dM = exp(-altitude / hm) * step;
        depthR += dR;
        depthM += dM;
        float cosZenith = dot(up, sunDirection);
        // Chapman-style air mass toward the sun; the planet's shadow ends the light softly past the terminator.
        float airMass = 1.0 / max(cosZenith + 0.12, 0.035);
        float lit = smoothstep(-0.12, 0.02, cosZenith);
        vec3 attenuation = exp(-(betaR * (depthR + exp(-altitude / hr) * hr * airMass)
                + betaM * 1.1 * (depthM + exp(-altitude / hm) * hm * airMass)));
        sumR += dR * attenuation * lit;
        sumM += dM * attenuation * lit;
    }
    float mu = dot(rayDirection, sunDirection);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = 0.76;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
            / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    Atmosphere result;
    result.inscatter = 11.0 * (sumR * betaR * phaseR + sumM * betaM * phaseM);
    result.transmittance = exp(-(betaR * depthR + betaM * 1.1 * depthM));
    return result;
}

// ---- earth ------------------------------------------------------------------------------------------------

vec3 earthSurface(vec3 position, vec3 viewDirection, vec3 sunDirection, vec3 center, float radius) {
    vec3 normal = normalize(position - center);
    vec3 local = toPlanet(normal, pc.view.z);
    vec2 e = equirect(local);
    vec4 surface = surfaceLayer(e);
    vec4 aux = auxLayer(e);
    float ocean = aux.r;

    // Relief: perturb the normal along the baked elevation gradient (east and north).
    float cl = max(length(local.xz), 1e-3);
    vec3 east = vec3(local.z, 0.0, -local.x) / cl;
    vec3 north = vec3(-local.y * local.x / cl, cl, -local.y * local.z / cl);
    float dEast = auxLayer(e + vec2(1.0 / 2048.0, 0.0)).g - auxLayer(e - vec2(1.0 / 2048.0, 0.0)).g;
    float dNorth = auxLayer(e - vec2(0.0, 1.0 / 1024.0)).g - auxLayer(e + vec2(0.0, 1.0 / 1024.0)).g;
    vec3 bumped = normalize(local - (east * dEast + north * dNorth) * 3.0 * (1.0 - ocean));
    vec3 shadingNormal = fromPlanet(bumped, pc.view.z);

    vec3 cloudLocal = toPlanet(normal, pc.view.z + pc.view.w);
    vec2 cloudUv = equirect(cloudLocal);
    float clouds = surfaceLayer(cloudUv).a;
    // Shadow of the cloud deck: sample the clouds displaced toward the sun along the surface.
    vec3 sunLocal = toPlanet(sunDirection, pc.view.z + pc.view.w);
    float shadow = surfaceLayer(equirect(normalize(cloudLocal + sunLocal * 0.012))).a;

    float ndl = dot(shadingNormal, sunDirection);
    float ndlSmooth = dot(normal, sunDirection);
    float daylight = smoothstep(-0.06, 0.22, ndlSmooth);
    vec3 sunColor = mix(vec3(1.0, 0.45, 0.2), vec3(1.0, 0.97, 0.92), smoothstep(0.0, 0.35, ndlSmooth));

    vec3 albedo = surface.rgb;
    vec3 ground = albedo * max(ndl, 0.0) * daylight * sunColor * (1.0 - shadow * 0.55);
    // Ocean glint: a tight sun reflection plus a broad sheen, both with Fresnel and hidden under clouds.
    vec3 viewVector = -viewDirection;
    vec3 halfway = normalize(sunDirection + viewVector);
    float ndh = max(dot(normal, halfway), 0.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(normal, viewVector), 0.0), 5.0);
    float glint = (pow(ndh, 420.0) * 6.0 + pow(ndh, 48.0) * 0.18) * ocean * (1.0 - clouds) * daylight;
    ground += sunColor * glint * (0.25 + fresnel) * (1.0 - aux.a);
    ground += vec3(0.004, 0.010, 0.022) * ocean * fresnel * daylight;

    vec3 cloudColor = vec3(0.95, 0.96, 1.0) * (smoothstep(-0.1, 0.4, ndlSmooth) * 1.15 + 0.004) * sunColor;
    vec3 color = mix(ground, cloudColor, clouds);

    // City lights where the night side is clear.
    float night = 1.0 - smoothstep(-0.18, 0.05, ndlSmooth);
    color += vec3(1.0, 0.58, 0.22) * aux.b * night * (1.0 - clouds * 0.9) * 0.9;
    return color;
}

// ---- satellite --------------------------------------------------------------------------------------------
// Modelled on photographs of a geostationary box-bus communications satellite (SSL 1300 class): a cube wrapped
// in crinkled metallic insulation, an adapter ring and engine on the aft face, two large ribbed reflectors on
// arms at the east and west faces, feeds on the Earth deck, and two long four-panel solar wings on yokes.
// Local axes: X east-west (reflectors), Y north-south (wings), +Z toward Earth, -Z aft.

const int FOIL = 0;
const int BLANKET = 1;
const int METAL = 2;
const int CELLS = 3;
const int REFLECTOR = 4;
const int DARK = 5;

struct Hit {
    float t;
    vec3 normal;   // local space
    int part;
    vec3 local;    // hit point in the part's frame (wing frame for cells)
    vec2 surface;  // 2D surface coordinates for patterns
};

void consider(inout Hit hit, float t, vec3 normal, int part, vec3 local, vec2 surface) {
    if (t > 0.0 && t < hit.t) {
        hit.t = t;
        hit.normal = normal;
        hit.part = part;
        hit.local = local;
        hit.surface = surface;
    }
}

float boxHit(vec3 origin, vec3 inverse, vec3 center, vec3 halfSize, out vec3 normal) {
    vec3 t0 = (center - halfSize - origin) * inverse;
    vec3 t1 = (center + halfSize - origin) * inverse;
    vec3 near = min(t0, t1);
    vec3 far = max(t0, t1);
    float entry = max(max(near.x, near.y), near.z);
    float exit = min(min(far.x, far.y), far.z);
    if (entry > exit || exit < 0.0) {
        return -1.0;
    }
    normal = -sign(inverse) * step(near.yzx, near.xyz) * step(near.zxy, near.xyz);
    return entry;
}

/** Capsule (segment a-b, radius r): struts, arms, yokes, feeds. */
float capsuleHit(vec3 o, vec3 d, vec3 a, vec3 b, float r, out vec3 normal) {
    vec3 ba = b - a;
    vec3 oa = o - a;
    float baba = dot(ba, ba);
    float bard = dot(ba, d);
    float baoa = dot(ba, oa);
    float rdoa = dot(d, oa);
    float oaoa = dot(oa, oa);
    float qa = baba - bard * bard;
    float qb = baba * rdoa - baoa * bard;
    float qc = baba * oaoa - baoa * baoa - r * r * baba;
    float h = qb * qb - qa * qc;
    if (h < 0.0) {
        return -1.0;
    }
    float t = (-qb - sqrt(h)) / qa;
    float y = baoa + t * bard;
    if (y > 0.0 && y < baba) {
        vec3 p = oa + t * d;
        normal = normalize(p - ba * (y / baba));
        return t;
    }
    vec3 oc = y <= 0.0 ? oa : o - b;
    float cb = dot(d, oc);
    float cc = dot(oc, oc) - r * r;
    h = cb * cb - cc;
    if (h < 0.0) {
        return -1.0;
    }
    t = -cb - sqrt(h);
    normal = normalize(oc + t * d);
    return t;
}

/** Open cylinder wall around the Z axis between two depths. */
float tubeZ(vec3 o, vec3 d, float radius, float z0, float z1, out vec3 normal) {
    float a = dot(d.xy, d.xy);
    float b = dot(o.xy, d.xy);
    float c = dot(o.xy, o.xy) - radius * radius;
    float h = b * b - a * c;
    if (a < 1e-8 || h < 0.0) {
        return -1.0;
    }
    h = sqrt(h);
    for (int i = 0; i < 2; i++) {
        float t = (-b + (i == 0 ? -h : h)) / a;
        float z = o.z + d.z * t;
        if (t > 0.0 && z > min(z0, z1) && z < max(z0, z1)) {
            vec2 p = o.xy + d.xy * t;
            normal = vec3(p / radius, 0.0);
            return t;
        }
    }
    return -1.0;
}

/** Flat annulus at depth z, facing -Z (aft). */
float annulusZ(vec3 o, vec3 d, float z, float inner, float outer) {
    if (abs(d.z) < 1e-6) {
        return -1.0;
    }
    float t = (z - o.z) / d.z;
    float r = length(o.xy + d.xy * t);
    return r >= inner && r <= outer ? t : -1.0;
}

void traceBus(vec3 o, vec3 d, vec3 inv, inout Hit hit) {
    vec3 n;
    float t = boxHit(o, inv, vec3(0.0), vec3(0.30, 0.34, 0.30), n);
    if (t > 0.0) {
        vec3 p = o + d * t;
        vec2 surface = abs(n.x) > 0.5 ? p.yz : abs(n.y) > 0.5 ? p.xz : p.xy;
        consider(hit, t, n, FOIL, p, surface);
    }
    // Aft face: launch adapter ring, thermal blanket, engine nozzle, and the ring's cross struts.
    t = tubeZ(o, d, 0.26, -0.30, -0.37, n);
    consider(hit, t, n, METAL, o + d * t, vec2(0.0));
    t = annulusZ(o, d, -0.37, 0.235, 0.26);
    consider(hit, t, vec3(0.0, 0.0, -1.0), METAL, o + d * t, vec2(0.0));
    t = annulusZ(o, d, -0.303, 0.075, 0.24);
    consider(hit, t, vec3(0.0, 0.0, -1.0), BLANKET, o + d * t, (o + d * t).xy);
    t = tubeZ(o, d, 0.075, -0.30, -0.42, n);
    consider(hit, t, n, DARK, o + d * t, vec2(0.0));
    t = annulusZ(o, d, -0.42, 0.0, 0.075);
    consider(hit, t, vec3(0.0, 0.0, -1.0), DARK, o + d * t, vec2(0.0));
    for (int i = 0; i < 4; i++) {
        float angle = float(i) * PI * 0.5 + PI * 0.25;
        vec2 dir = vec2(cos(angle), sin(angle));
        t = capsuleHit(o, d, vec3(dir * 0.085, -0.33), vec3(dir * 0.245, -0.33), 0.008, n);
        consider(hit, t, n, METAL, o + d * t, vec2(0.0));
    }
    // Earth deck: two feed horns aimed at the reflectors and an omni antenna mast.
    for (int s = -1; s <= 1; s += 2) {
        float side = float(s);
        t = capsuleHit(o, d, vec3(side * 0.14, 0.02, 0.30), vec3(side * 0.22, 0.02, 0.42), 0.055, n);
        consider(hit, t, n, BLANKET, o + d * t, vec2(0.0));
    }
    t = capsuleHit(o, d, vec3(0.10, 0.20, 0.30), vec3(0.10, 0.20, 0.66), 0.012, n);
    consider(hit, t, n, METAL, o + d * t, vec2(0.0));
}

void traceReflectors(vec3 o, vec3 d, inout Hit hit) {
    const float SPHERE = 1.1;
    const float RIM = 0.50;
    float depth = sqrt(SPHERE * SPHERE - RIM * RIM);
    for (int s = -1; s <= 1; s += 2) {
        float side = float(s);
        vec3 axis = normalize(vec3(side * 0.45, 0.0, 1.0));
        vec3 rim = vec3(side * 0.90, 0.02, 0.16);
        vec3 center = rim + axis * depth;
        vec2 shell = sphereHit(o, d, center, SPHERE);
        for (int i = 0; i < 2; i++) {
            float t = i == 0 ? shell.x : shell.y;
            vec3 p = o + d * t;
            if (t > 0.0 && t < hit.t && dot(p - center, -axis) > depth) {
                vec3 e1 = normalize(cross(axis, vec3(0.0, 1.0, 0.0)));
                vec3 e2 = cross(axis, e1);
                vec2 surface = vec2(dot(p - rim, e1), dot(p - rim, e2)) / RIM;
                consider(hit, t, normalize(center - p), REFLECTOR, p, surface);
                break;
            }
        }
        // Two arms from the bus edges to the reflector's back.
        vec3 n;
        float t = capsuleHit(o, d, vec3(side * 0.30, 0.26, 0.05), rim + vec3(0.0, 0.22, -0.10), 0.014, n);
        consider(hit, t, n, METAL, o + d * t, vec2(0.0));
        t = capsuleHit(o, d, vec3(side * 0.30, -0.26, 0.05), rim + vec3(0.0, -0.22, -0.10), 0.014, n);
        consider(hit, t, n, METAL, o + d * t, vec2(0.0));
    }
}

void traceWings(vec3 o, vec3 d, float wingAngle, inout Hit hit) {
    // Wing frame: rotated about Y so the cells' +Z faces the sun.
    float c = cos(wingAngle);
    float s = sin(wingAngle);
    vec3 wo = vec3(c * o.x - s * o.z, o.y, s * o.x + c * o.z);
    vec3 wd = vec3(c * d.x - s * d.z, d.y, s * d.x + c * d.z);
    vec3 inv = 1.0 / wd;
    for (int side = -1; side <= 1; side += 2) {
        float y = float(side);
        vec3 n;
        float t = capsuleHit(wo, wd, vec3(0.12, y * 0.34, 0.0), vec3(0.0, y * 0.64, 0.0), 0.012, n);
        consider(hit, t, vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z), METAL, wo + wd * t, vec2(0.0));
        t = capsuleHit(wo, wd, vec3(-0.12, y * 0.34, 0.0), vec3(0.0, y * 0.64, 0.0), 0.012, n);
        consider(hit, t, vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z), METAL, wo + wd * t, vec2(0.0));
        for (int panel = 0; panel < 4; panel++) {
            float centerY = y * (0.64 + 0.31 + float(panel) * 0.645);
            t = boxHit(wo, inv, vec3(0.0, centerY, 0.0), vec3(0.28, 0.31, 0.008), n);
            if (t > 0.0 && t < hit.t) {
                vec3 p = wo + wd * t;
                // Back to the satellite frame for lighting; the pattern stays in wing coordinates.
                consider(hit, t, vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z), CELLS, p,
                         vec2(p.x, p.y - centerY));
            }
        }
    }
}

Hit traceSatellite(vec3 o, vec3 d, float wingAngle) {
    Hit hit;
    hit.t = 1e9;
    hit.part = -1;
    vec3 inv = 1.0 / d;
    traceBus(o, d, inv, hit);
    traceReflectors(o, d, hit);
    traceWings(o, d, wingAngle, hit);
    return hit;
}

/** What a mirror-like surface at worldHit sees along a direction: the planet (lit or night) or black sky. */
vec3 environment(vec3 origin, vec3 direction, vec3 sunDirection, vec3 center, float radius) {
    vec2 earth = sphereHit(origin, direction, center, radius * 1.02);
    if (earth.x > 0.0) {
        vec3 normal = normalize(origin + direction * earth.x - center);
        float lit = smoothstep(-0.1, 0.4, dot(normal, sunDirection));
        return vec3(0.06, 0.12, 0.26) * lit + vec3(0.004, 0.006, 0.012);
    }
    return vec3(0.002, 0.002, 0.004);
}

/** Voronoi crinkles: each cell of the foil is a small flat facet with its own tilt, as crumpled MLI is. */
vec3 crinkle(vec2 p, out float cellShade, out float gold) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    float nearest = 8.0;
    vec2 nearestCell = cell;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = cell + vec2(x, y);
            vec2 point = vec2(x, y) + vec2(hash21(c), hash21(c + 19.7)) - f;
            float distanceToPoint = dot(point, point);
            if (distanceToPoint < nearest) {
                nearest = distanceToPoint;
                nearestCell = c;
            }
        }
    }
    // Facets lean around a slowly varying fold direction, so crinkles run in streaks like real blankets.
    vec2 fold = vec2(noise3(vec3(p * 0.08, 1.0)), noise3(vec3(p * 0.08, 9.0))) - 0.5;
    vec2 tilt = vec2(hash21(nearestCell + 3.3), hash21(nearestCell + 8.1)) - 0.5;
    cellShade = 0.94 + 0.12 * hash21(nearestCell + 5.5);
    // Whole blankets of amber (gold-tinted) film beside silver ones.
    gold = step(0.55, noise3(vec3(floor(p / 14.0), 4.0)));
    return vec3(tilt * 0.55 + fold * 0.8, 1.0);
}

vec3 shadeSatellite(Hit hit, vec3 rayDirection, mat3 orientation, vec3 sunDirection, vec3 center, float radius) {
    vec3 localNormal = hit.normal;
    if (dot(orientation * localNormal, rayDirection) > 0.0) {
        localNormal = -localNormal; // thin, two-sided parts
    }
    vec3 normal = orientation * localNormal;
    vec3 worldHit = rayDirection * hit.t;
    float sunlight = sphereHit(worldHit, sunDirection, center, radius).x > 0.0 ? 0.0 : 1.0;
    vec3 base;
    float roughness;
    float metal;
    float transmit = 0.0;
    if (hit.part == FOIL) {
        float shade;
        float gold;
        vec3 facet = crinkle(hit.surface * 70.0, shade, gold);
        // Tilt the face normal by the facet slope, in two tangents of the face.
        vec3 tangent = abs(localNormal.y) < 0.9 ? normalize(cross(localNormal, vec3(0.0, 1.0, 0.0))) : vec3(1.0, 0.0, 0.0);
        vec3 bitangent = cross(localNormal, tangent);
        normal = normalize(orientation * (localNormal * facet.z + tangent * facet.x + bitangent * facet.y));
        // Tape seams between blankets.
        vec2 seam = abs(fract(hit.surface * vec2(3.2, 2.9)) - 0.5);
        float tape = smoothstep(0.47, 0.49, max(seam.x, seam.y));
        base = mix(vec3(0.88, 0.87, 0.84), vec3(0.95, 0.68, 0.30), gold) * shade;
        base = mix(base, vec3(0.30), tape);
        roughness = 0.08;
        metal = 1.0;
    } else if (hit.part == BLANKET) {
        base = vec3(0.80);
        roughness = 0.7;
        metal = 0.0;
    } else if (hit.part == METAL) {
        base = vec3(0.62, 0.62, 0.64);
        roughness = 0.35;
        metal = 1.0;
    } else if (hit.part == CELLS) {
        bool front = hit.local.z > 0.0; // wing frame: cells face +Z, toward the sun
        vec2 cells = abs(fract(hit.surface / vec2(0.045, 0.06)) - 0.5);
        float gaps = smoothstep(0.43, 0.48, max(cells.x, cells.y));
        float busBar = 1.0 - smoothstep(0.004, 0.008, abs(abs(hit.surface.x) - 0.14));
        float frame = smoothstep(0.26, 0.272, abs(hit.surface.x)) + smoothstep(0.29, 0.302, abs(hit.surface.y));
        base = front ? mix(vec3(0.010, 0.016, 0.050), vec3(0.20, 0.21, 0.23), max(gaps * 0.6, busBar))
                     : mix(vec3(0.55, 0.55, 0.56), vec3(0.40), gaps * 0.5);
        base = mix(base, vec3(0.45), clamp(frame, 0.0, 1.0));
        roughness = front ? 0.10 : 0.8;
        metal = front ? 0.25 : 0.0;
    } else if (hit.part == REFLECTOR) {
        // Ribs: a three-by-three grid and the rim, darker against the pale mesh.
        vec2 ribs = abs(hit.surface * 2.0 - round(hit.surface * 2.0));
        float rib = 1.0 - smoothstep(0.012, 0.03, min(ribs.x, ribs.y));
        float rimRing = smoothstep(0.93, 0.97, length(hit.surface));
        base = mix(vec3(0.86, 0.68, 0.36), vec3(0.22, 0.21, 0.20), max(rib, rimRing));
        roughness = 0.55;
        metal = 0.2;
        // Gold-clad wire mesh is partly transparent: lit from behind, the sun glows through it.
        transmit = (1.0 - max(rib, rimRing)) * 0.55;
    } else {
        base = vec3(0.06);
        roughness = 0.5;
        metal = 0.5;
    }
    vec3 viewVector = -rayDirection;
    float ndl = max(dot(normal, sunDirection), 0.0) * sunlight;
    vec3 halfway = normalize(sunDirection + viewVector);
    float shininess = 2.0 / max(roughness * roughness, 0.002);
    vec3 f0 = mix(vec3(0.04), base, metal);
    float fresnel = pow(1.0 - max(dot(halfway, viewVector), 0.0), 5.0);
    vec3 fresnelColor = f0 + (1.0 - f0) * fresnel;
    vec3 specular = fresnelColor * min(pow(max(dot(normal, halfway), 0.0), shininess) * (shininess + 2.0) / 8.0, 60.0) * ndl;
    vec3 diffuse = base * (1.0 - metal) * ndl;
    // Mirror-like parts reflect what is around them: mostly black sky, the planet's blue, sometimes the sun.
    vec3 reflected = environment(worldHit, reflect(rayDirection, normal), sunDirection, center, radius) * fresnelColor * metal;
    vec3 toEarth = normalize(center - worldHit);
    float earthLit = 0.25 + 0.5 * max(dot(-toEarth, sunDirection), 0.0);
    vec3 earthshine = vec3(0.07, 0.12, 0.24) * max(dot(normal, toEarth), 0.0) * earthLit;
    // Backlit edges: surfaces seen at a grazing angle while facing into the sun pick up a thin rim of light.
    float grazing = pow(1.0 - abs(dot(normal, viewVector)), 4.0);
    float behind = max(dot(rayDirection, sunDirection), 0.0);
    vec3 rim = vec3(1.0, 0.9, 0.75) * grazing * behind * sunlight * 1.4;
    vec3 transmitted = base * transmit * max(dot(-normal, sunDirection), 0.0) * sunlight * 2.2;
    return diffuse * 2.8 + specular * 2.4 + reflected * 3.0 + earthshine * base * (1.0 - metal * 0.6) * 2.4
            + rim * mix(vec3(1.0), base, 0.5) + transmitted + base * 0.01;
}

// ---- composition ------------------------------------------------------------------------------------------

vec3 tonemap(vec3 color) {
    color *= pc.extra.y;
    return clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
}

vec3 rayAt(vec2 screenUv) {
    vec2 ndc = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
    return normalize(vec3(ndc.x * pc.view.x * pc.view.y, ndc.y * pc.view.y, -1.0));
}

/** The sun: a white-hot disk, a warm glow, and faint six-point diffraction spikes; hidden behind the planet. */
vec3 sunGlare(vec3 direction, vec3 sunDirection, vec2 screenUv, vec3 center, float radius) {
    float cosAngle = dot(direction, sunDirection);
    float angle = acos(clamp(cosAngle, -1.0, 1.0));
    float hidden = sphereHit(vec3(0.0), sunDirection, center, radius).x > 0.0 ? 1.0 : 0.0;
    // Near the limb the sun's light still blooms through the atmosphere, so only the disk and spikes vanish.
    float disk = (1.0 - smoothstep(0.0095, 0.0115, angle)) * 70.0;
    vec3 glow = vec3(1.0, 0.86, 0.66) * (exp(-angle * 80.0) * 2.0 + exp(-angle * 16.0) * 0.16 + exp(-angle * 4.0) * 0.018);
    vec3 spikes = vec3(0.0);
    if (sunDirection.z < 0.0) {
        vec2 sunNdc = vec2(sunDirection.x / (-sunDirection.z) / (pc.view.x * pc.view.y),
                           sunDirection.y / (-sunDirection.z) / pc.view.y);
        vec2 ndc = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
        vec2 offset = (ndc - sunNdc) * vec2(pc.view.x, 1.0);
        float r = length(offset);
        float phi = atan(offset.y, offset.x) + 0.3;
        float rays = pow(abs(cos(phi * 3.0)), 1400.0);
        spikes = vec3(1.0, 0.92, 0.8) * rays * exp(-r * 16.0) * 0.35;
    }
    return (vec3(disk) + spikes) * (1.0 - hidden) + glow * (1.0 - hidden * 0.85);
}

vec3 background(vec3 direction, vec3 sunDirection, float time, vec3 center, float radius) {
    vec3 color = starfield(direction, time);
    vec2 earthHit = sphereHit(vec3(0.0), direction, center, radius);
    vec2 shell = sphereHit(vec3(0.0), direction, center, radius * 1.028);
    if (shell.y > 0.0) {
        float start = max(shell.x, 0.0);
        float end = earthHit.x > 0.0 ? earthHit.x : shell.y;
        Atmosphere air = atmosphere(direction, start, end, center, radius, sunDirection);
        if (earthHit.x > 0.0) {
            vec3 surface = earthSurface(direction * earthHit.x, direction, sunDirection, center, radius);
            color = surface * air.transmittance + air.inscatter;
        } else {
            color = color * air.transmittance + air.inscatter;
        }
    }
    return color;
}

void main() {
    vec3 sunDirection = normalize(pc.sun.xyz);
    float time = pc.sun.w;
    vec3 center = pc.earth.xyz;
    float radius = pc.earth.w;
    vec3 direction = rayAt(uv);
    vec3 color = background(direction, sunDirection, time, center, radius);
    if (sphereHit(vec3(0.0), direction, center, radius).x < 0.0) {
        color += sunGlare(direction, sunDirection, uv, center, radius);
    }

    mat3 orientation = mat3(pc.satellite);
    vec3 position = pc.satellite[3].xyz;
    float scale = pc.satellite[3].w;
    // Cheap reject: only pixels near the satellite's bounding sphere trace it.
    if (sphereHit(vec3(0.0), direction, position, pc.extra.x * scale * 1.05).y > 0.0) {
        vec3 accumulated = vec3(0.0);
        float coverage = 0.0;
        vec2 offsets[4] = vec2[](vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(0.125, 0.375), vec2(-0.375, 0.125));
        vec3 localOrigin = transpose(orientation) * (-position) / scale;
        float earthT = sphereHit(vec3(0.0), direction, center, radius).x;
        for (int s = 0; s < 4; s++) {
            vec3 d = rayAt(uv + offsets[s] * pc.extra.z * vec2(1.0 / pc.view.x, 1.0));
            vec3 localDirection = transpose(orientation) * d;
            Hit hit = traceSatellite(localOrigin, localDirection, pc.extra.w);
            if (hit.part >= 0) {
                hit.t *= scale;
                if (earthT < 0.0 || hit.t < earthT) {
                    accumulated += shadeSatellite(hit, d, orientation, sunDirection, center, radius);
                    coverage += 0.25;
                }
            }
        }
        if (coverage > 0.0) {
            color = mix(color, accumulated / (coverage * 4.0), coverage);
        }
    }
    outColor = vec4(tonemap(color), 1.0);
}
