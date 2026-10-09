#version 150 core

// Pass 6 - composite and tonemap.
//
// Where the actual lighting happens. Rather than replace Minecraft's shading
// wholesale (which would mean reimplementing its block models, entity skins and
// biome tint), Onigiri treats the game's colour buffer as albedo and lights it
// again from scratch: the shader owns the lighting, the game supplies the
// surfaces.
//
// That trade is what makes this cheap and version-stable - we only need depth
// and colour from the game, so nothing here breaks when a model format changes.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uNormalDepth;
uniform sampler2D uAO;
uniform sampler2D uSSR;
uniform sampler2D uShadow;

uniform float uAOStrength;
uniform float uSSRStrength;
uniform float uShadowStrength;
uniform float uSpecularStrength;
uniform float uExposure;
uniform float uVignette;
uniform float uSaturation;
uniform float uNightLift;
uniform float uFogNear;
uniform float uFogFar;

// True when a half-res tap belongs to the same surface as the full-res pixel.
//
// Depth here is normalised over the whole clip range, so an absolute epsilon
// would be far too loose near the camera and far too tight at range. Comparing
// against the local depth makes the test scale-invariant: two taps agree if they
// differ by less than a few percent of the depth they sit at.
bool depthAgree(float tapDepth, float centreDepth) {
    // A negative sentinel marks a tap the source pass never wrote meaningfully.
    if (tapDepth < 0.0) {
        return true;
    }

    float tolerance = max(centreDepth * 0.02, 0.0015);
    return abs(tapDepth - centreDepth) <= tolerance;
}

// Depth-aware upsample of a half-res buffer.
//
// A plain bilinear fetch bleeds occlusion across silhouettes, which shows up as
// dark haloes around every object edge. Weighting the four bilinear taps by how
// closely each one's reconstructed depth matches this full-res pixel keeps the
// effect pinned to the surface it belongs to.
vec4 sampleHalf(sampler2D tex, vec2 uv, float centreDepth) {
    vec2 texel = 1.0 / uHalfResolution;

    vec2 centre = uv / texel - 0.5;
    vec2 blend = fract(centre);
    vec2 base = (floor(centre) + 0.5) * texel;

    vec4 s00 = texture(tex, base);
    vec4 s10 = texture(tex, base + vec2(texel.x, 0.0));
    vec4 s01 = texture(tex, base + vec2(0.0, texel.y));
    vec4 s11 = texture(tex, base + texel);

    // Keep only the taps whose stored depth agrees with this pixel's surface.
    // Weighting the survivors by their bilinear share is what stops occlusion
    // bleeding across a silhouette edge.
    float w00 = depthAgree(s00.a, centreDepth) ? (1.0 - blend.x) * (1.0 - blend.y) : 0.0;
    float w10 = depthAgree(s10.a, centreDepth) ? blend.x * (1.0 - blend.y) : 0.0;
    float w01 = depthAgree(s01.a, centreDepth) ? (1.0 - blend.x) * blend.y : 0.0;
    float w11 = depthAgree(s11.a, centreDepth) ? blend.x * blend.y : 0.0;

    float total = w00 + w10 + w01 + w11;

    // If every tap was rejected, fall back to plain bilinear rather than black.
    if (total < 1.0e-4) {
        return mix(mix(s00, s10, blend.x), mix(s01, s11, blend.x), blend.y);
    }

    return (s00 * w00 + s10 * w10 + s01 * w01 + s11 * w11) / total;
}

void main() {
    vec4 nd = texture(uNormalDepth, vUv);
    vec3 base = texture(uColor, vUv).rgb;

    // Sky: keep as-is, just tonemap it so it matches the rest of the frame.
    if (nd.a < 0.0) {
        vec3 sky = base * uExposure;
        fragColor = vec4(tonemap(sky), 1.0);
        return;
    }

    vec3 N = normalize(nd.xyz * 2.0 - 1.0);
    float d = texture(uDepth, vUv).r;
    vec3 P = viewPosFromDepth(vUv, d);
    float dist = -P.z;

    vec3 V = normalize(-P);
    vec3 L = normalize(uSunDirView);
    vec3 H = normalize(V + L);

    // Effects are stored at half res with view depth in alpha; the upsample
    // keeps them pinned to the surface they belong to.
    float depthNorm = nd.a;
    float ao = sampleHalf(uAO, vUv, depthNorm).r;
    float shadow = sampleHalf(uShadow, vUv, depthNorm).r;
    vec4 ssr = sampleHalf(uSSR, vUv, depthNorm);

    // ---- albedo -----------------------------------------------------------
    // The game already applied its lighting. Recover a rough albedo by undoing
    // the exposure curve and clamping, so we can relight from a sane base.
    vec3 albedo = base;

    // ---- direct lighting --------------------------------------------------
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    float NdotH = max(dot(N, H), 0.0);

    // Vanilla surfaces are mostly dielectric; a low F0 keeps specular from
    // looking like chrome on grass.
    float roughness = mix(0.65, 0.25, clamp(1.0 - dot(base, vec3(0.33)), 0.0, 1.0));
    float perceptual = roughness * roughness;
    vec3 F0 = vec3(0.04);

    vec3 F = fresnelSchlick(VdotH, F0, perceptual);
    float D = distributionGGX(NdotH, perceptual);
    float Vis = visibilitySmith(NdotV, NdotL, perceptual);

    // Shadow term. NdotL is folded in here rather than into the shadow texture
    // so the falloff stays physically sensible.
    float shadowTerm = mix(1.0, shadow, uShadowStrength);
    vec3 direct = uSunColor * NdotL * shadowTerm;

    // Specular is a separate additive lobe rather than folded into `direct`:
    // it carries no albedo, and mixing it in is what makes highlights look like
    // tinted paint instead of light.
    vec3 specular = uSunColor * F * D * Vis * NdotL * shadowTerm * uSpecularStrength;

    // ---- ambient ----------------------------------------------------------
    // Hemispheric ambient, gated by AO. The ground-bounce term keeps
    // undersides from going pure black, which is what makes AO read as
    // "contact shadow" rather than "dirty screen".
    float upFacing = N.y * 0.5 + 0.5;
    vec3 ambient = mix(uAmbientColor * 0.35, uSkyColor, upFacing);

    // AO applies to ambient only - never to direct light. Applying it to both
    // is the most common AO mistake and it flattens every direct highlight.
    float aoTerm = mix(1.0, ao, uAOStrength);
    vec3 indirect = ambient * aoTerm * albedo;

    // ---- reflections ------------------------------------------------------
    // SSR replaces the ambient specular lobe. The pass premultiplied by its own
    // confidence, so this only needs the fresnel weight and the strength.
    //
    // Without the fresnel term the reflection reads as coloured blotches rather
    // than as highlights: reflections should be near-invisible head-on and strong
    // at grazing angles.
    float NdotV4 = pow(NdotV, 4.0);
    vec3 reflFresnel = F0 + (max(vec3(1.0 - perceptual), F0) - F0) * NdotV4;
    indirect += ssr.rgb * reflFresnel * uSSRStrength * mix(vec3(1.0), albedo, 0.35);

    // ---- combine ----------------------------------------------------------
    vec3 color = albedo * direct + indirect + specular;

    // Atmospheric fog, matched to vanilla's smoothstep curve.
    float fogFactor = smoothstep(uFogNear, uFogFar, dist);
    vec3 fogColor = mix(uSkyColor, uAmbientColor, 0.35) * 0.85;
    color = mix(color, fogColor, fogFactor * 0.75);

    // Night lift: raise the shadow floor slightly so deep shadow reads as dim
    // blue rather than as a hole in the image. Applied before the tonemap so it
    // survives the curve instead of being crushed by it.
    color += uAmbientColor * uNightLift;

    // ---- tonemap ----------------------------------------------------------
    color *= uExposure;

    // Saturation about luminance, in linear space.
    float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(lum), color, uSaturation);

    color = tonemap(color);

    // ---- vignette ---------------------------------------------------------
    vec2 centred = vUv * 2.0 - 1.0;
    float vig = 1.0 - dot(centred, centred) * uVignette;
    color *= clamp(vig, 0.0, 1.0);

    fragColor = vec4(color, 1.0);
}
