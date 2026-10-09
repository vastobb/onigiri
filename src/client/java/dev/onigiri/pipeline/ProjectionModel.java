package dev.onigiri.pipeline;

import java.lang.reflect.Method;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;

/**
 * Builds the projection, view and view-projection matrices for the current frame,
 * together with their inverses.
 *
 * <p>Minecraft 26.x keeps the projection in a {@code ProjectionMatrixBuffer} and
 * rebuilds the view matrix each frame, so neither is readable as a plain field.
 * Reading them would need a mixin into {@code GameRenderer} that breaks whenever
 * Mojang renames something.
 *
 * <p>Reflection is used instead, on purpose. A renamed method degrades the effect
 * rather than crashing the client with an injection failure - for a
 * post-process renderer, failing soft is the right behaviour. If resolution
 * fails, the caller skips the frame and tries again next time.
 *
 * <p>The reconstructed matrices are equivalent for this pipeline's purposes:
 * every effect samples the depth buffer at known pixels and unprojects them, so
 * any projection that agrees with the depth buffer at those same pixels is
 * equally correct.
 *
 * <p><strong>Forward and inverse are built together here, once.</strong> That is
 * the whole point of this class. The previous version called {@code invert()} on
 * the inverse matrices without ever assigning the forward matrices into them,
 * so all three stayed at identity and every {@code viewPosFromDepth} call in the
 * shaders returned untransformed NDC.
 */
public final class ProjectionModel {
	private static final float FALLBACK_NEAR = 0.05f;
	private static final float FALLBACK_FAR = 1024.0f;

	/**
	 * Reflection targets resolved once.
	 *
	 * <p>Every accessor on {@code Method} and {@code getMethods()} returns a fresh
	 * array, so the previous version - which called {@code getMethods()} on the
	 * delta tracker and on the renderer twice per frame - allocated a copy of the
	 * entire method table at 60 Hz. That is a steady stream of garbage in the
	 * render loop, and on Android the collector runs at the worst possible moment.
	 */
	private static Method gameRendererGetMainCamera;
	private static Method gameRendererGetFov;
	private static Method gameRendererGetDepthFar;
	private static Method clientGetFovSetting;
	private static Method deltaTrackerPartialTick;
	private static boolean reflectionResolved;

	private static final Matrix4f SCRATCH_VIEW = new Matrix4f();

	/** Scratch for the camera-to-world matrix, inverted into the view. */
	private static final Matrix4f SCRATCH_WORLD = new Matrix4f();

	/** Scratch for the direction transform, so the render loop stays allocation-free. */
	private static final Vector4f SCRATCH_DIRECTION = new Vector4f();

	private ProjectionModel() {
	}

	/**
	 * Resolves every reflected member this class needs, exactly once.
	 *
	 * <p>A missing member is not fatal: the corresponding value falls back to a
	 * sensible default and the effect degrades instead of the client crashing.
	 */
	private static synchronized void resolveReflection() {
		if (reflectionResolved) {
			return;
		}

		reflectionResolved = true;

		try {
			Class<?> renderer = Class.forName("net.minecraft.client.renderer.GameRenderer");
			gameRendererGetMainCamera = findMethod(renderer, "getMainCamera", 0);
			gameRendererGetFov = findMethod(renderer, "getFOV", 3);
			gameRendererGetDepthFar = findMethod(renderer, "getDepthFar", 0);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// Every projection value falls back to the configured FOV.
		}

		try {
			Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
			clientGetFovSetting = findMethod(minecraft, "getFovSetting", 0);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// Falls back to 70 degrees.
		}

		// Vanilla's partial tick lives behind a different accessor name depending
		// on the version. Both spellings are looked up once rather than scanned
		// for every frame.
		try {
			Class<?> tracker = Class.forName("net.minecraft.client.DeltaTracker");
			deltaTrackerPartialTick = findMethod(tracker, "getGameTimeDeltaPartialTick", 0);

			if (deltaTrackerPartialTick == null) {
				deltaTrackerPartialTick = findMethod(tracker, "getGameTimeDeltaPartialTicks", 0);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// Falls back to zero, which costs a little smoothness in the sun's
			// motion and nothing else.
		}
	}

	private static Method findMethod(Class<?> owner, String name, int parameterCount) {
		for (Method method : owner.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
				return method;
			}
		}

		return null;
	}

	private static Object invoke(Method method, Object target, Object... args) {
		if (method == null || target == null) {
			return null;
		}

		try {
			return method.invoke(target, args);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Reads the render interpolation factor, used to make the sun's motion
	 * smooth rather than stepping at the tick rate.
	 *
	 * @return the partial tick, or 0 when it cannot be resolved
	 */
	public static float partialTick(Minecraft client) {
		resolveReflection();

		if (client == null || deltaTrackerPartialTick == null) {
			return 0.0f;
		}

		Object tracker;

		try {
			tracker = client.getDeltaTracker();
		} catch (RuntimeException | LinkageError e) {
			return 0.0f;
		}

		Object result = invoke(deltaTrackerPartialTick, tracker);

		return result instanceof Number n ? n.floatValue() : 0.0f;
	}

	/**
	 * Fills {@code frame}'s matrices from the client's renderer.
	 *
	 * @return {@code true} when the matrices are usable this frame
	 */
	public static boolean update(FrameState frame, Minecraft client, float partialTick) {
		if (frame == null || client == null) {
			return false;
		}

		float aspect = aspectRatio(client);
		float fov = effectiveFov(client, partialTick);

		if (!(aspect > 0.0f)) {
			return false;
		}

		resolveClipPlanes(frame, client);
		return buildMatrices(frame, aspect, fov);
	}

	private static float aspectRatio(Minecraft client) {
		try {
			int width = client.getWindow().getWidth();
			int height = client.getWindow().getHeight();

			if (width <= 0 || height <= 0) {
				return 0.0f;
			}

			return width / (float) height;
		} catch (RuntimeException | LinkageError e) {
			return 0.0f;
		}
	}

	/**
	 * Reads the effective vertical FOV, which folds in sprint and speed effects.
	 * Falls back to the configured option, then to a sane default.
	 */
	private static float effectiveFov(Minecraft client, float partialTick) {
		resolveReflection();

		try {
			Object renderer = client.gameRenderer;

			if (renderer != null && gameRendererGetMainCamera != null) {
				Object camera = invoke(gameRendererGetMainCamera, renderer);

				if (camera != null && gameRendererGetFov != null) {
					// getFOV(Camera, float partialTick, boolean)
					Object fov = invoke(gameRendererGetFov, renderer, camera, partialTick, Boolean.TRUE);

					if (fov instanceof Number n && n.floatValue() > 1.0f) {
						return n.floatValue();
					}
				}
			}
		} catch (RuntimeException | LinkageError e) {
			// Fall through to the option value.
		}

		Object fovOption = invoke(clientGetFovSetting, client);

		if (fovOption instanceof Number n) {
			float degrees = n.floatValue();
			if (degrees > 1.0f && degrees < 179.0f) {
				return degrees;
			}
		}

		return 70.0f;
	}

	/**
	 * Reads the far plane so fog lines up with the configured render distance.
	 *
	 * <p>On a phone this also bounds the effect work: the depth-aware fade in
	 * every pass keys off the same range, so a long render distance costs fill
	 * even where nothing visible changes.
	 */
	private static void resolveClipPlanes(FrameState frame, Minecraft client) {
		frame.near = FALLBACK_NEAR;
		frame.far = FALLBACK_FAR;

		resolveReflection();

		Object depthFar;

		try {
			depthFar = invoke(gameRendererGetDepthFar, client.gameRenderer);
		} catch (RuntimeException | LinkageError e) {
			return;
		}

		if (depthFar instanceof Number n && n.floatValue() > frame.near * 16.0f) {
			frame.far = n.floatValue();
		}
	}

	/** Builds the projection, view and view-projection, plus every inverse. */
	public static boolean buildMatrices(FrameState frame, float aspect, float fovDegrees) {
		if (!(aspect > 0.0f) || !(fovDegrees > 1.0f) || !(fovDegrees < 179.0f)) {
			return false;
		}

		if (!(frame.near > 0.0f) || !(frame.far > frame.near)) {
			return false;
		}

		float fovRadians = (float) Math.toRadians(fovDegrees);

		frame.projection.identity();
		frame.projection.perspective(fovRadians, aspect, frame.near, frame.far);

		viewMatrix(frame, SCRATCH_VIEW);

		// A degenerate camera would poison every downstream inversion, so reject
		// it here rather than rendering noise.
		if (!isFinite(SCRATCH_VIEW)) {
			return false;
		}

		// Forward matrices. Both are assigned before either is inverted, so the
		// inverses below are genuinely inverses of these and not of whatever the
		// fields happened to hold before.
		frame.view.set(SCRATCH_VIEW);
		frame.viewProjection.set(frame.projection).mul(SCRATCH_VIEW);

		if (!isFinite(frame.viewProjection)) {
			return false;
		}

		// Inverse matrices.
		frame.inverseProjection.set(frame.projection).invert();
		frame.viewInverse.set(frame.view).invert();
		frame.inverseViewProjection.set(frame.viewProjection).invert();

		if (!isFinite(frame.inverseProjection)
				|| !isFinite(frame.viewInverse)
				|| !isFinite(frame.inverseViewProjection)) {
			return false;
		}

		// The shadow and sky passes march in view space, so they need the sun there
		// too. That depends on the view matrix, which is why it happens here rather
		// than in LightingModel.
		//
		// A direction, not a point: w is zero, so the perspective divide in the
		// matrix-vector product cannot skew it. Transforming via Vector4f is also
		// the only correct way here - the previous code cast the 4x4 view matrix to
		// Matrix3fc to reach the 3x3 overload, which it does not implement. That
		// threw a ClassCastException on the very first rendered frame, which the
		// client treated as fatal and used to switch the whole renderer off.
		SCRATCH_DIRECTION.set(frame.sunDirection, 0.0f).mul(frame.view);

		frame.sunDirectionView.set(SCRATCH_DIRECTION.x, SCRATCH_DIRECTION.y, SCRATCH_DIRECTION.z);

		float lengthSquared = frame.sunDirectionView.lengthSquared();

		// A zero-length result means the sun is exactly at the camera; any
		// direction is as good as another and dividing would produce NaN.
		if (lengthSquared > 1.0e-12f && Float.isFinite(lengthSquared)) {
			frame.sunDirectionView.div((float) Math.sqrt(lengthSquared));
		} else {
			frame.sunDirectionView.set(0.0f, 0.0f, 1.0f);
		}

		return true;
	}

	/**
	 * Builds the view matrix from the camera basis.
	 *
	 * <p>Vanilla convention, verified against the game's own angle mapping: yaw 0
	 * faces +Z (south), yaw 90 faces -X (west), positive pitch looks down. That
	 * gives forward = (-sinYaw·cosPitch, -sinPitch, cosYaw·cosPitch), right =
	 * (-cosYaw, 0, -sinYaw) - facing south, the right hand points west - and up
	 * = (-sinYaw·sinPitch, cosPitch, cosYaw·sinPitch). The three are orthonormal
	 * and right-handed (right × up = back), which was checked numerically rather
	 * than assumed: the previous basis pointed down at level pitch and used the
	 * forward vector where right belonged.
	 *
	 * <p>The view is built by inverting the camera-to-world matrix (columns are
	 * right, up, back, eye) rather than by hand-assembling the inverse. A rigid
	 * transform inverts exactly, so there is no room for a transposed rotation or
	 * a mistranslated eye - both of which the previous version had, setting the
	 * translation to +eye instead of the rotated -eye. Written into {@code out}
	 * (via the shared scratch) so the render loop stays garbage-free.
	 */
	private static void viewMatrix(FrameState frame, Matrix4f out) {
		float yaw = (float) Math.toRadians(frame.cameraYaw);
		float pitch = (float) Math.toRadians(frame.cameraPitch);

		float sinYaw = (float) Math.sin(yaw);
		float cosYaw = (float) Math.cos(yaw);
		float sinPitch = (float) Math.sin(pitch);
		float cosPitch = (float) Math.cos(pitch);

		float rightX = -cosYaw;
		float rightY = 0.0f;
		float rightZ = -sinYaw;

		float upX = -sinYaw * sinPitch;
		float upY = cosPitch;
		float upZ = cosYaw * sinPitch;

		float backX = sinYaw * cosPitch;
		float backY = sinPitch;
		float backZ = -cosYaw * cosPitch;

		// Camera-to-world: basis vectors as columns, eye as translation.
		// JOML's set() takes columns, so this reads as four column vectors.
		SCRATCH_WORLD.set(
				rightX, rightY, rightZ, 0.0f,
				upX, upY, upZ, 0.0f,
				backX, backY, backZ, 0.0f,
				frame.cameraPos.x, frame.cameraPos.y, frame.cameraPos.z, 1.0f);

		out.set(SCRATCH_WORLD).invert();
	}

	/** True when no component is NaN or infinite. */
	private static boolean isFinite(Matrix4f matrix) {
		return Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
				&& Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
				&& Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
				&& Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
				&& Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
				&& Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
				&& Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
				&& Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
	}
}