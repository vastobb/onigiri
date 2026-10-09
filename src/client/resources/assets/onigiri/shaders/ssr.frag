// Pass 3 - screen-space reflections at half resolution.
//
// Marches a ray in view space, re-projecting each step back to screen space and
// comparing against the depth buffer. Rays that leave the screen, or that miss
// entirely, fall back to the analytic sky in common.glsl so the result is never
// a hole. Roughness fades the reflection out, which is what keeps a cheap
// raymarch from looking like a mirror.
//
// SSR is the most expensive pass here - it is the only one that walks a long
// ray per pixel - so the step count is the main quality dial and the default
// preset on mobile keeps it low.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uNormalDepth;
uniform float uMaxRoughness;   // above this, skip the trace entirely
uniform int   uSteps;
uniform float uMaxDistance;
uniform float uThickness;

void main() {
    vec4 nd = texture(uNormalDepth, vUv);

    if (nd.a < 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 N = normalize(nd.xyz * 2.0 - 1.0);
    float depthNorm = nd.a;
    float d = texture(uDepth, vUv).r;
    vec3 P = viewPosFromDepth(vUv, d);
    vec3 V = normalize(-P);

    // Minecraft has no material buffer, so roughness is derived from the local
    // colour. Flat, dark texels read as smooth (water, obsidian); bright, busy
    // ones read as rough (grass, leaves).
    vec3 albedo = texture(uColor, vUv).rgb;
    float lum = dot(albedo, vec3(0.2126, 0.7152, 0.0722));

    float roughness = clamp(1.0 - lum * 1.15, 0.05, 1.0);

    // Too rough to reflect meaningfully. Skipping the trace entirely is the
    // cheapest win available: most of a typical frame is rough surfaces, and they
    // contribute nothing here anyway.
    if (roughness > uMaxRoughness) {
        fragColor = vec4(0.0, 0.0, 0.0, depthNorm);
        return;
    }

    vec3 R = reflect(-V, N);

    // Force the ray upward a touch. Without this, near-horizontal rays spend
    // their whole budget sliding across the same pixels and mostly miss.
    R = normalize(R + vec3(0.0, roughness * 0.35, 0.0));

    // View space looks down -Z, so a ray heading away from the camera can never
    // hit anything on screen. Fall back to the sky along its own direction.
    if (R.z > -1e-4) {
        fragColor = vec4(skyRadiance(toWorldDir(R)) * 0.35 * (1.0 - roughness), depthNorm);
        return;
    }

    float jitter = ign(gl_FragCoord.xy + uFrame * 7.13);

    vec3 hitColor = vec3(0.0);
    float hitFound = 0.0;

    for (int i = 0; i < 48; i++) {
        if (i >= uSteps) break;

        // Quadratic step growth: fine near the origin, coarse further out.
        float t = (float(i) + jitter) / float(uSteps);
        t = t * t;
        float travelled = t * uMaxDistance;

        vec3 pos = P + N * 0.05 + R * travelled;

        vec4 clip = uProj * vec4(pos, 1.0);
        if (clip.w <= 0.0) break;

        vec2 suv = (clip.xy / clip.w) * 0.5 + 0.5;

        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) {
            // Off screen: the sky along this ray is the honest answer.
            hitColor = skyRadiance(toWorldDir(normalize(R)));
            hitFound = 0.6;
            break;
        }

        float sceneDepth = texture(uDepth, suv).r;

        if (isSky(sceneDepth)) {
            hitColor = skyRadiance(toWorldDir(normalize(R)));
            hitFound = 1.0;
            break;
        }

        vec3 scenePos = viewPosFromDepth(suv, sceneDepth);

        // The ray is in front of the surface only if its view-space z is closer
        // to the camera than what the depth buffer recorded there.
        float diff = scenePos.z - pos.z;   // both negative; less negative = nearer

        if (diff > 0.0 && diff < uThickness * (1.0 + travelled * 0.05)) {
            // Refine: step back toward the surface a little for a cleaner edge.
            vec3 refine = mix(pos, scenePos, 0.5);
            vec4 rclip = uProj * vec4(refine, 1.0);
            vec2 rsuv = (rclip.xy / rclip.w) * 0.5 + 0.5;

            bool inBounds = rsuv.x >= 0.0 && rsuv.x <= 1.0 && rsuv.y >= 0.0 && rsuv.y <= 1.0;
            hitColor = inBounds ? texture(uColor, rsuv).rgb : skyRadiance(toWorldDir(R));

            hitFound = 1.0;
            break;
        }

        // Ran out of steps without a hit. The sky is the honest answer here: the
        // ray left the screen or travelled past everything the depth buffer knows.
        hitColor = skyRadiance(toWorldDir(R));
    }

    // Rough surfaces reflect less; confidence approximates the blur we cannot
    // afford to gather. It also falls off toward the far plane, where the depth
    // buffer cannot resolve a hit reliably.
    float confidence = hitFound * (1.0 - roughness);
    confidence *= 1.0 - smoothstep(0.75, 1.0, depthNorm);

    // rgb is premultiplied by confidence, so the composite adds it directly
    // without needing to know how the trace went. Alpha carries view depth
    // instead, which is what the depth-aware upsample there needs.
    fragColor = vec4(hitColor * confidence, depthNorm);
}