// Pass 1 - geometry reconstruction.
//
// Minecraft hands us a colour buffer and a depth buffer, nothing else. This pass
// rebuilds the one thing every later pass needs: a view-space normal per pixel.
// Normals come from the "improved" derivative scheme (pick the closer of the
// forward/backward pair, which avoids the classic quantisation stair-stepping on
// near-flat surfaces).
//
// This is the only pass that runs at full resolution, so it is also the most
// expensive one on a phone. Keeping it to four depth fetches and two matrix
// transforms is what makes the half-res chain downstream affordable.

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

// Rebuilds a view-space normal from the local depth gradient.
vec3 reconstructNormal(vec2 uv, vec3 centrePos) {
    vec2 texel = 1.0 / uResolution;

    float dR = texture(uDepth, uv + vec2(texel.x, 0.0)).r;
    float dL = texture(uDepth, uv - vec2(texel.x, 0.0)).r;
    float dU = texture(uDepth, uv + vec2(0.0, texel.y)).r;
    float dD = texture(uDepth, uv - vec2(0.0, texel.y)).r;

    // Forward differences.
    vec3 dxF = viewPosFromDepth(uv + vec2(texel.x, 0.0), dR) - centrePos;
    vec3 dyF = viewPosFromDepth(uv + vec2(0.0, texel.y), dU) - centrePos;

    // Backward differences.
    vec3 dxB = centrePos - viewPosFromDepth(uv - vec2(texel.x, 0.0), dL);
    vec3 dyB = centrePos - viewPosFromDepth(uv - vec2(0.0, texel.y), dD);

    // Whichever pair is shorter in magnitude is the more trustworthy estimate,
    // and carries the correct sign for free.
    vec3 dx = (dot(dxF, dxF) < dot(dxB, dxB)) ? dxF : dxB;
    vec3 dy = (dot(dyF, dyF) < dot(dyB, dyB)) ? dyF : dyB;

    // The gradient is measured in view space, where the camera looks down -Z.
    vec3 n = cross(dx, dy);

    // A degenerate gradient - a surface seen exactly edge-on, or a depth plateau
    // at a silhouette - gives a zero vector. Normalizing that yields NaN, which
    // then poisons every tap that samples this pixel. Fall back to facing the
    // camera rather than emitting garbage.
    float len = length(n);

    if (len < EPS) {
        return vec3(0.0, 0.0, 1.0);
    }

    return normalize(n) * -1.0;
}

void main() {
    float d = texture(uDepth, vUv).r;

    if (isSky(d)) {
        // Sky has no surface. a = -1 marks it so later passes can bail out.
        fragColor = vec4(0.0, 0.0, 0.0, -1.0);
        return;
    }

    vec3 P = viewPosFromDepth(vUv, d);
    vec3 N = reconstructNormal(vUv, P);

    // Pack: rgb = view normal, a = view depth normalised into [0,1].
    // The normalised depth rides in alpha so the depth-aware upsample in the
    // composite can tell which surface a half-res tap belongs to.
    float depthNorm = clamp((-P.z - uNear) / max(uFar - uNear, EPS), 0.0, 1.0);
    fragColor = vec4(N * 0.5 + 0.5, depthNorm);
}