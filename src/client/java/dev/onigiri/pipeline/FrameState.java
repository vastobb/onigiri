package dev.onigiri.pipeline;

import org.joml.Vector3f;

/**
 * Per-frame state shared by every pass.
 *
 * <p>Mutable by design: the values are produced once on the render thread by
 * {@link LightingModel} and {@link ProjectionModel}, then read by six passes.
 * Copying eight matrices per frame would be pure waste for state that never
 * escapes the render thread.
 *
 * <p>The previous view-projection is carried alongside the current one because
 * the temporal pass needs it to reproject history, and keeping a copy is far
 * cheaper than re-deriving it.
 *
 * <p>Every matrix here is authoritative as set by {@link ProjectionModel}: the
 * pipeline only reads them. An earlier version inverted the "inverse" matrices
 * in place without ever copying the forward matrix into them, which silently
 * left all three inverses as the identity and made every depth reconstruction
 * in the shader return raw NDC. Constructing forward and inverse together, in
 * one place, is what prevents that class of bug recurring.
 */
public final class FrameState {
	public final org.joml.Matrix4f projection = new org.joml.Matrix4f();
	public final org.joml.Matrix4f inverseProjection = new org.joml.Matrix4f();
	public final org.joml.Matrix4f view = new org.joml.Matrix4f();
	public final org.joml.Matrix4f viewInverse = new org.joml.Matrix4f();
	public final org.joml.Matrix4f viewProjection = new org.joml.Matrix4f();
	public final org.joml.Matrix4f inverseViewProjection = new org.joml.Matrix4f();
	public final org.joml.Matrix4f previousViewProjection = new org.joml.Matrix4f();

	/** Camera position in world space. */
	public final Vector3f cameraPos = new Vector3f();

	/** Camera yaw in degrees, from the camera entity. */
	public float cameraYaw;

	/** Camera pitch in degrees, from the camera entity. */
	public float cameraPitch;

	/** World-space direction pointing towards the sun, normalised. */
	public final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);

	/** The same direction in view space. */
	public final Vector3f sunDirectionView = new Vector3f(0.0f, 0.0f, 1.0f);

	public final Vector3f sunColor = new Vector3f(1.0f, 0.95f, 0.85f);
	public final Vector3f skyColor = new Vector3f(0.35f, 0.52f, 0.85f);
	public final Vector3f ambientColor = new Vector3f(0.12f, 0.13f, 0.16f);

	public float near;
	public float far;
	public float time;
	public long frameCounter;

	/** True while the camera jumped far enough that history should be discarded. */
	public boolean resetHistory;

	public void advanceFrame(float time) {
		previousViewProjection.set(viewProjection);
		this.time = time;
		this.frameCounter++;
	}

	/** Requests that the temporal pass discard its history on the next frame. */
	public void requestHistoryReset() {
		resetHistory = true;
	}
}