#version 150 core

// Pass 1 - geometry reconstruction.
//
// Minecraft hands us a colour buffer and a depth buffer, nothing else. This pass
// rebuilds the one thing every later pass needs: a view-space position and a
// surface normal per pixel. Normals come from the "improved" derivative scheme
// (pick the closer of the forward/backward pair, which avoids the classic
// quantisation stair-stepping on near-flat surfaces).

#include "common.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform vec2 uTexelSize;

// Rebuilds a view-space normal from the local depth gradient.
vec3 reconstructNormal(vec2 uv, float centreDepth, vec3 centrePos) {
    float dR = texture(uDepth, uv + vec2(uTexelSize.x, 0.0)).r;
    float dL = texture(uDepth, uv - vec2(uTexelSize.x, 0.0)).r;
    float dU = texture(uDepth, uv + vec2(0.0, uTexelSize.y)).r;
    float dD = texture(uDepth, uv - vec2(0.0, uTexelSize.y)).r;

    // Forward differences.
    vec3 dxF = viewPosFromDepth(uv + vec2(uTexelSize.x, 0.0), dR) - centrePos;
    vec3 dyF = viewPosFromDepth(uv + vec2(0.0, uTexelSize.y), dU) - centrePos;

    // Backward differences.
    vec3 dxB = centrePos - viewPosFromDepth(uv - vec2(uTexelSize.x, 0.0), dL);
    vec3 dyB = centrePos - viewPosFromDepth(uv - vec2(0.0, uTexelSize.y), dD);

    // Whichever pair is shorter in magnitude is the more trustworthy estimate,
    // and carries the correct sign for free.
    vec3 dx = (dot(dxF, dxF) < dot(dxB, dxB)) ? dxF : dxB;
    vec3 dy = (dot(dyF, dyF) < dot(dyB, dyB)) ? dyF : dyB;

    // The gradient is measured in view space, where the camera looks down -Z.
    return normalize(cross(dx, dy)) * -1.0;
}

void main() {
    float d = texture(uDepth, vUv).r;

    if (isSky(d)) {
        // Sky has no surface. a = -1 marks it so later passes can bail out.
        fragColor = vec4(0.0, 0.0, 0.0, -1.0);
        return;
    }

    vec3 P = viewPosFromDepth(vUv, d);
    vec3 N = reconstructNormal(vUv, d, P);

    // Pack: rgb = view normal, a = view depth normalised into [0,1] so the
    // buffer stays inside 8 bits without banding.
    float depthNorm = clamp((-P.z - uNear) / max(uFar - uNear, EPS), 0.0, 1.0);
    fragColor = vec4(N * 0.5 + 0.5, depthNorm);
}
