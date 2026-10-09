#version 150 core

// Pass 5 - temporal resolve.
//
// Merges this frame's half-res AO, SSR and shadow results with the history
// buffer. Three jobs:
//
//   1. Carry forward pixels the shadow pass skipped this frame (-1 sentinel).
//   2. Reproject last frame's result through the previous view-projection, so
//      history survives camera motion.
//   3. Clamp history against the local neighbourhood to kill ghosting.
//
// This is what turns a quarter-rate effect into a full-rate one, and it is the
// single reason the pipeline is affordable.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uCurrent;
uniform sampler2D uHistory;
uniform sampler2D uNormalDepth;

uniform vec2  uSourceTexel;      // texel size of the half-res source
uniform float uFeedback;         // 0..1, how much history to trust
uniform float uReset;            // 1.0 forces a hard cut (teleport, resize)
uniform int   uHasHistory;

/**
 * Box filter over the 3x3 neighbourhood, returning the mean and the half-width
 * of a variance-derived box around it.
 *
 * The half-width is per-channel and derived from the mean and mean-of-squares,
 * which gives a cheap variance estimate without a second pass. This box is the
 * standard anti-ghosting clamp: history that falls outside it cannot have come
 * from the same surface as this pixel, so it is rejected.
 */
void neighbourhoodBox(sampler2D tex, vec2 uv, vec2 texel, float gamma, out vec3 mean, out vec3 halfExtent) {
    vec3 sum = vec3(0.0);
    vec3 sumSq = vec3(0.0);

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec3 s = texture(tex, uv + vec2(float(x), float(y)) * texel).rgb;
            sum += s;
            sumSq += s * s;
        }
    }

    mean = sum / 9.0;
    vec3 meanSq = sumSq / 9.0;

    vec3 sigma = sqrt(max(meanSq - mean * mean, vec3(0.0)));

    // A floor on the box width. Without it a perfectly flat neighbourhood yields
    // a zero-width box and rejects every history sample, which reads as flicker.
    halfExtent = max(gamma * sigma, vec3(1.0 / 255.0));
}

/** Largest per-channel box half-width, used as a cheap contrast measure. */
float meanExtent(vec3 halfExtent) {
    return (halfExtent.r + halfExtent.g + halfExtent.b) / 3.0;
}

void main() {
    vec4 current = texture(uCurrent, vUv);

    // The shadow pass marks the pixels it skipped this frame with -1. Those are
    // the ones that make the quarter-rate schedule work: reproject and carry
    // their previous answer instead of tracing a ray for them.
    bool carried = current.r < 0.0;

    if (uReset > 0.5 || uHasHistory == 0) {
        // No history to carry from. Substitute the unoccluded value rather than
        // letting the sentinel escape into the buffer.
        fragColor = carried ? vec4(1.0, 1.0, 1.0, current.a) : current;
        return;
    }

    vec4 nd = texture(uNormalDepth, vUv);

    // Sky: nothing to reproject, and no history is meaningful.
    if (nd.a < 0.0) {
        fragColor = carried ? vec4(1.0, 1.0, 1.0, current.a) : current;
        return;
    }

    float d = texture(uDepth, vUv).r;
    vec3 worldPos = worldPosFromDepth(vUv, d);

    // Reproject into last frame's clip space.
    vec4 prevClip = uPrevViewProj * vec4(worldPos, 1.0);

    if (prevClip.w <= 0.0) {
        fragColor = current;
        return;
    }

    vec2 prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;

    // Off screen: no history to use.
    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        fragColor = current;
        return;
    }

    vec4 history = texture(uHistory, prevUv);

    // A skipped pixel carries its previous answer verbatim. Reprojection already
    // placed that answer at the right pixel, and clamping it against a
    // neighbourhood built from sentinel values would only inject noise.
    if (carried) {
        fragColor = history;
        return;
    }

    // Neighbourhood clamp: validate the reprojected history against a
    // variance-derived box around the current 3x3 neighbourhood. Without it a
    // moving object smears its old value across the screen.
    vec3 mean;
    vec3 halfExtent;
    neighbourhoodBox(uCurrent, vUv, uSourceTexel, 1.5, mean, halfExtent);

    vec3 historyRgb = history.rgb;
    vec3 lower = mean - halfExtent;
    vec3 upper = mean + halfExtent;

    bool historyValid = all(greaterThanEqual(historyRgb, lower)) && all(lessThanEqual(historyRgb, upper));

    float feedback = uFeedback;

    if (!historyValid) {
        // Fall back to the neighbourhood mean rather than the raw history value:
        // this bleeds instead of ghosts.
        historyRgb = mean;
        feedback *= 0.55;
    }

    // Adaptive feedback. Low contrast areas (already converged) converge fast;
    // high contrast areas (in motion) trust the present more.
    float variance = meanExtent(halfExtent);
    feedback *= mix(1.0, 0.7, clamp(variance * 4.0, 0.0, 1.0));

    fragColor = vec4(mix(current.rgb, historyRgb, clamp(feedback, 0.0, 0.97)), current.a);
}
