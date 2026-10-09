#version 150 core

// Pass 2 - horizon-based ambient occlusion at half resolution.
//
// This is the first of the two effects that make Onigiri cheap. GTAO-style
// occlusion is expensive per pixel if sampled densely, so instead we run a
// small, fixed number of directions per pixel and let temporal accumulation
// (pass 5) average the rest. Each pixel only ever does a handful of taps.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uNormalDepth;
uniform int   uDirections;      // horizon slices per pixel
uniform float uRadius;          // world-space search radius
uniform int   uSteps;           // taps along each slice

// Largest angle any occluder subtends at this point, found by walking a slice
// through the depth buffer and tracking the highest neighbour it passes.
float sliceOcclusion(vec3 P, vec3 N, float radius, float rot, float sliceFrac) {
    // Slice direction in the screen plane, rotated per pixel so that temporal
    // accumulation fills in the directions this pixel skipped.
    float angle = rot + sliceFrac * 2.0 * PI;
    vec2 screenDir = vec2(cos(angle), sin(angle));

    float horizon = -1.0;

    for (int i = 1; i <= 8; i++) {
        if (i > uSteps) break;

        // Quadratic step distribution: dense near the pixel, where the contact
        // signal lives, sparse in the far tail where only large occluders reach.
        float t = float(i) / float(uSteps);
        t = t * t;

        // March in screen space by an amount that corresponds to `radius * t`
        // world units at this depth. Converting through the projection keeps
        // the search radius perceptually constant with distance.
        vec2 offset = screenDir * (radius * t) * projScale() / max(-P.z, uNear);
        vec2 suv = vUv + offset;

        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) continue;

        float sampleDepth = texture(uDepth, suv).r;
        if (isSky(sampleDepth)) continue;

        vec3 sampleViewPos = viewPosFromDepth(suv, sampleDepth);
        vec3 diff = sampleViewPos - P;

        float len = length(diff);
        if (len < EPS) continue;

        // How far this neighbour rises above the tangent plane. Positive means
        // it sits above the surface and blocks sky light.
        horizon = max(horizon, dot(diff / len, N));
    }

    // Square the term to tighten the falloff around the contact region, which is
    // what makes this read as a contact shadow rather than a grey wash.
    float occlusion = clamp(horizon, 0.0, 1.0);
    return occlusion * occlusion;
}

void main() {
    vec4 nd = texture(uNormalDepth, vUv);

    if (nd.a < 0.0) {
        fragColor = vec4(1.0);
        return;
    }

    vec3 N = normalize(nd.xyz * 2.0 - 1.0);
    float depthNorm = nd.a;
    float d = texture(uDepth, vUv).r;
    vec3 P = viewPosFromDepth(vUv, d);

    // Scale the world-space radius with distance so the effect stays stable from
    // the player's hand out to the render distance.
    float dist = max(-P.z, uNear);
    float radius = uRadius * clamp(dist / 16.0, 0.35, 4.0);

    float rot = frameRotation() * 2.0 * PI;
    float occ = 0.0;

    for (int i = 0; i < 8; i++) {
        if (i >= uDirections) break;
        float sliceFrac = (float(i) + 0.5) / float(uDirections);
        occ += sliceOcclusion(P, N, radius, rot, sliceFrac);
    }

    occ /= float(max(uDirections, 1));

    // Fade out with distance: past this the depth buffer has no precision left
    // to say anything meaningful about occlusion.
    occ *= 1.0 - smoothstep(0.55, 1.0, depthNorm);

    // rgb = visibility (1 = open, 0 = occluded), a = this pixel's view depth.
    // The alpha is what lets the composite pass upsample this without bleeding
    // occlusion across silhouettes.
    fragColor = vec4(vec3(clamp(1.0 - occ, 0.0, 1.0)), depthNorm);
}
