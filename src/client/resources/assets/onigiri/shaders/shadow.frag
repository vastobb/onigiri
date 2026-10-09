#version 150 core

// Pass 4 - shadows, and the reason Onigiri runs at a small fraction of vanilla's
// cost while still looking better than anything that samples a shadow map.
//
// The idea: instead of rendering a shadow map (an extra geometry pass plus a
// comparison per lit pixel) we march a short ray towards the sun through the
// depth buffer we already have. Screen-space shadows only cover what is on
// screen, but for contact and mid-range occlusion - which is where a shadow map
// spends nearly all of its quality budget - that is enough.
//
// The cost trick is temporal amortisation. Each frame only a rotating
// blue-noise subset of pixels actually traces a ray; every other pixel keeps its
// answer from the history buffer, reprojected through last frame's matrices.
// A quarter of the pixels at full precision, upsampled, is visually
// indistinguishable from all of them at a quarter of the price.
//
// Cost per frame is therefore ~ (steps / 4) taps per pixel at half resolution,
// and it spreads across four frames. That is the trade.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uNormalDepth;
uniform int   uSteps;
uniform float uMaxDistance;
uniform float uThickness;
uniform float uSoftness;

// Subset of pixels that traces this frame. Chosen by interleaved gradient noise
// offset per frame, which walks the screen in a well-distributed order instead
// of scattering randomly (no clumping, no visible per-frame shimmer).
bool activeThisFrame(vec2 fragCoord) {
    float noise = ign(fragCoord + vec2(uFrame * 11.13, uFrame * 17.71));
    return noise < 0.25;
}

void main() {
    vec4 nd = texture(uNormalDepth, vUv);

    if (nd.a < 0.0) {
        // Sky is always lit. Alpha is the far plane, which is what the
        // depth-aware upsample in the composite expects here.
        fragColor = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }

    float depthNorm = nd.a;
    float d = texture(uDepth, vUv).r;
    vec3 P = viewPosFromDepth(vUv, d);
    vec3 N = normalize(nd.xyz * 2.0 - 1.0);

    // Project the sun direction into view space and normalise it.
    vec3 L = normalize(uSunDirView);

    // NdotL decides how much light this surface could receive at all. Surfaces
    // facing away skip the march entirely - a free early out on roughly half the
    // screen at any given sun angle.
    float NdotL = dot(N, L);

    if (NdotL <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, depthNorm);
        return;
    }

    if (!activeThisFrame(gl_FragCoord.xy)) {
        // Not this frame's turn. The negative red channel is the sentinel the
        // temporal pass looks for; alpha still carries depth so the composite's
        // upsample has something to work with if this buffer is read directly.
        fragColor = vec4(-1.0, -1.0, -1.0, depthNorm);
        return;
    }

    // Start the ray just above the surface, offset along the normal to avoid
    // self-shadowing acne on shallow angles.
    vec3 origin = P + N * (0.02 + (1.0 - NdotL) * 0.12);

    float visibility = 1.0;
    float jitter = hash12(gl_FragCoord.xy + uFrame * 3.7);
    float stepLen = uMaxDistance / float(uSteps);

    for (int i = 0; i < 24; i++) {
        if (i >= uSteps) break;

        // Jittered start offset breaks up the banding that fixed-step marches
        // produce on smooth gradients.
        float t = float(i) + jitter;
        vec3 sp = origin + L * (t * stepLen);

        vec4 clip = uProj * vec4(sp, 1.0);
        if (clip.w <= 0.0) break;

        vec2 suv = (clip.xy / clip.w) * 0.5 + 0.5;

        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) break;

        float sceneDepth = texture(uDepth, suv).r;

        // No occluder out there.
        if (isSky(sceneDepth)) continue;

        vec3 scenePos = viewPosFromDepth(suv, sceneDepth);
        float diff = scenePos.z - sp.z;

        // Thicker tolerance further along the ray: perspective means a given
        // depth error spans more world space at range.
        float tolerance = uThickness * (1.0 + t * stepLen * 0.08);

        if (diff > 0.0 && diff < tolerance) {
            // Soften with distance along the ray so distant occluders cast
            // penumbra instead of hard edges.
            float penumbra = clamp(t / float(uSteps), 0.0, 1.0);
            visibility = min(visibility, mix(1.0, 0.0, 1.0 - penumbra * uSoftness));
        }
    }

    // Fade the effect out at range: past this the depth buffer is too coarse to
    // resolve a shadow, and a wrong shadow is worse than none.
    visibility *= 1.0 - smoothstep(0.6, 0.95, depthNorm);

    fragColor = vec4(vec3(clamp(visibility, 0.0, 1.0)), depthNorm);
}
