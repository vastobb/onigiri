package dev.onigiri.pipeline;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Everything the passes need to know about the current frame, captured once on
 * the render thread and passed around as an immutable value.
 *
 * <p>The previous frame's view-projection matrix is carried alongside the
 * current one: the temporal pass needs it to reproject history, and keeping a
 * copy is far cheaper than re-deriving it.
 */
public final class FrameState {
	public int width;
	public int height;

	public Matrix4f projection = new Matrix4f();
	public Matrix4f inverseProjection = new Matrix4f();
	public Matrix4f view = new Matrix4f();
	public Matrix4f viewInverse = new Matrix4f();
	public Matrix4f viewProjection = new Matrix4f();
	public Matrix4f inverseViewProjection = new Matrix4f();
	public Matrix4f previousViewProjection = new Matrix4f();

	public final Vector3f cameraPos = new Vector3f();

	/** Camera yaw in degrees, from the camera entity. */
	public float cameraYaw;

	/** Camera pitch in degrees, from the camera entity. */
	public float cameraPitch;

	/** Vertical field of view in degrees, including sprint and speed effects. */
	public float fov = 70.0f;

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

	public float halfWidth() {
		return Math.max(1.0f, width * 0.5f);
	}

	public float halfHeight() {
		return Math.max(1.0f, height * 0.5f);
	}
}
