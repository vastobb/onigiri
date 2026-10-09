#ifndef ONIGIRI_COMMON_GLSL
#define ONIGIRI_COMMON_GLSL

// Shared helpers for every Onigiri pass.
// Matrices arrive in clip space and depth is the standard [0,1] window-space value,
// so reconstruction is done by unprojecting rather than by hand-rolled linearisation.

const float PI  = 3.141592653589793;
const float EPS = 1e-6;

uniform sampler2D uDepth;
uniform sampler2D uColor;

uniform mat4  uProj;
uniform mat4  uInvProj;
uniform mat4  uView;
uniform mat4  uInvView;
uniform mat4  uViewProj;
uniform mat4  uInvViewProj;
uniform mat4  uPrevViewProj;

uniform vec2  uResolution;       // full-res target size in pixels
uniform vec2  uHalfResolution;   // half-res target size in pixels
uniform vec3  uCameraPos;
uniform vec3  uSunDir;           // world space, points *towards* the sun
uniform vec3  uSunDirView;       // the same direction in view space
uniform vec3  uSunColor;
uniform vec3  uSkyColor;
uniform vec3  uAmbientColor;
uniform float uNear;
uniform float uFar;
uniform float uFrame;            // monotonically increasing frame counter
uniform float uTime;

// ---------------------------------------------------------------- hashing --

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Interleaved gradient noise: cheap, and its spectrum is far friendlier to
// temporal accumulation than white noise would be.
float ign(vec2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}

// Per-pixel rotation that walks the whole circle over consecutive frames.
float frameRotation() {
    return ign(gl_FragCoord.xy + vec2(uFrame * 5.588238, uFrame * 3.141593));
}

// -------------------------------------------------------------- geometry --

bool isSky(float d) {
    return d >= 0.999999;
}

vec3 viewPosFromDepth(vec2 uv, float d) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 v = uInvProj * ndc;
    return v.xyz / v.w;
}

vec3 worldPosFromDepth(vec2 uv, float d) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 w = uInvViewProj * ndc;
    return w.xyz / w.w;
}

float linearDepth(float d) {
    float z = d * 2.0 - 1.0;
    return (2.0 * uNear * uFar) / (uFar + uNear - z * (uFar - uNear));
}

// Converts a world-space offset at view depth d into a UV offset.
// The projection's [1][1] term is 1/tan(fovY/2), and UV spans half of NDC, so a
// world offset of `w` at depth d covers w * P11 / (2d) in UV. Deriving this from
// uProj keeps the search radius perceptually constant at any FOV.
float projScale() {
    return uProj[1][1] * 0.5;
}

// ------------------------------------------------------------- sky model --

// Cheap analytic sky used as the fallback when a reflection ray escapes the
// screen. Deliberately not a cubemap: this is a handful of instructions, and it
// always agrees with the direction the ray was actually heading.
//
// Takes and returns a WORLD-space direction. Reflection rays are traced in view
// space, so callers convert with `toWorldDir`.
vec3 skyRadiance(vec3 worldDir) {
    float up = clamp(worldDir.y * 0.5 + 0.5, 0.0, 1.0);
    float horizon = pow(1.0 - abs(worldDir.y), 4.0);

    vec3 base = mix(uAmbientColor * 0.45, uSkyColor, smoothstep(0.0, 1.0, up));

    // Warm scatter around the sun: tight near the disc, broad near the horizon.
    float sunDot = max(dot(worldDir, uSunDir), 0.0);
    base += uSunColor * (pow(sunDot, 8.0) * 0.35 + pow(sunDot, 2.0) * horizon * 0.55);

    return base;
}

// Converts a view-space direction to world space.
//
// Uses the inverse view rotation only. Dividing by the full inverse
// view-projection would fold in the perspective divide and skew every direction
// that is not axis-aligned.
vec3 toWorldDir(vec3 viewDir) {
    return normalize((uInvView * vec4(viewDir, 0.0)).xyz);
}

// ------------------------------------------------------------------ brdf --

// GGX / Trowbridge-Reitz normal distribution plus the Smith height-correlated
// visibility term and Schlick fresnel. Everything is written out so the
// composite pass never has to touch a lookup table.
float distributionGGX(float NdotH, float roughness) {
    float a  = roughness * roughness;
    float a2 = a * a;
    float d  = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, EPS);
}

float visibilitySmith(float NdotV, float NdotL, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float lv = NdotL * sqrt(NdotV * NdotV * (1.0 - a2) + a2);
    float ll = NdotV * sqrt(NdotL * NdotL * (1.0 - a2) + a2);
    return 0.5 / max(lv + ll, EPS);
}

vec3 fresnelSchlick(float VdotH, vec3 F0, float roughness) {
    vec3 F = F0 + (vec3(1.0) - F0) * pow(clamp(1.0 - VdotH, 0.0, 1.0), 5.0);
    // Rough surfaces lose the mirror component of the highlight.
    return F * (1.0 - roughness * 0.65);
}

// -------------------------------------------------------------- tonemap ----

// ACES filmic approximation (Krzysztof Narkowicz). Chosen over Reinhard because
// it keeps saturated highlights from shifting hue as they clip, which matters a
// lot once bloom and specular are pushing values well above 1.0.
vec3 tonemap(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;

    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

#endif
