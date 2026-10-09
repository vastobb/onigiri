#version 150 core

// Fullscreen triangle. No vertex buffer required - positions are derived from gl_VertexID,
// which GLSL 1.50 exposes. A bare (empty) VAO must still be bound in core profile.

out vec2 vUv;

void main() {
    // 0 -> (0,0), 1 -> (2,0), 2 -> (0,2): one oversized triangle covering the viewport.
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
