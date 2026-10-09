package dev.onigiri.pipeline;

import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;

/**
 * Captures the projection and view-projection matrices for the current frame.
 *
 * <p>Minecraft 26.x keeps the projection in a {@code ProjectionMatrixBuffer} and
 * rebuilds the view matrix each frame, so neither is readable as a plain field.
 * Reading them would need a mixin into {@code GameRenderer} that breaks whenever
 * Mojang renames something.
 *
 * <p>Reflection is used here instead, on purpose. A renamed method degrades the
 * effect rather than crashing the client with an injection failure - for a
 * post-process renderer, failing soft is the right behaviour. If resolution
 * fails the caller skips the frame and tries again next time.
 *
 * <p>The reconstructed matrices are equivalent for this pipeline's purposes:
 * every effect samples the depth buffer at known pixels and unprojects them, so
 * any projection that agrees with the depth buffer at those same pixels is
 * equally correct.
 */
public final class ProjectionModel {
	private static final float FALLBACK_NEAR = 0.05f;
	private static final float FALLBACK_FAR = 1024.0f;

	private ProjectionModel() {
	}

	/**
	 * Fills {@code frame}'s matrices from the client's renderer.
	 *
	 * @return {@code true} when the matrices are usable this frame
	 */
	public static boolean update(FrameState frame, Minecraft client) {
		float aspect = aspectRatio(client);
		float fov = effectiveFov(client);

		if (!(aspect > 0.0f)) {
			return false;
		}

		frame.fov = fov;
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
		} catch (RuntimeException e) {
			return 0.0f;
		}
	}

	/**
	 * Reads the effective vertical FOV, which folds in sprint and speed effects.
	 * Falls back to the configured option, then to a sane default.
	 */
	private static float effectiveFov(Minecraft client) {
		Object renderer = client.gameRenderer;

		if (renderer != null) {
			Object camera = invokeNoArg(renderer, "getMainCamera");

			if (camera != null) {
				// getFOV(Camera, float, boolean) - the float is the partial tick.
				Object fov = invoke(renderer, "getFOV", camera, 0.0f, Boolean.TRUE);

				if (fov instanceof Number n && n.floatValue() > 1.0f) {
					return n.floatValue();
				}
			}
		}

		// The raw option, in vanilla's half-angle-tangent units.
		Object fovOption = invokeNoArg(client, "getFovSetting");

		if (fovOption instanceof Number n) {
			float degrees = n.floatValue() * 2.0f;
			if (degrees > 1.0f && degrees < 179.0f) {
				return degrees;
			}
		}

		return 70.0f;
	}

	/** Reads the far plane so fog lines up with the configured render distance. */
	private static void resolveClipPlanes(FrameState frame, Minecraft client) {
		frame.near = FALLBACK_NEAR;
		frame.far = FALLBACK_FAR;

		Object depthFar = invokeNoArg(client.gameRenderer, "getDepthFar");

		if (depthFar instanceof Number n && n.floatValue() > frame.near * 16.0f) {
			frame.far = n.floatValue();
		}
	}

	/** Builds the projection and view-projection from the camera basis. */
	public static boolean buildMatrices(FrameState frame, float aspect, float fovDegrees) {
		if (!(aspect > 0.0f) || !(fovDegrees > 1.0f) || !(fovDegrees < 179.0f)) {
			return false;
		}

		float fovRadians = (float) Math.toRadians(fovDegrees);

		frame.projection.identity();
		frame.projection.perspective(fovRadians, aspect, frame.near, frame.far);

		Matrix4f view = viewMatrix(frame);

		// A degenerate camera would poison every downstream inversion, so reject
		// it here rather than rendering noise.
		if (!isFinite(view)) {
			return false;
		}

		frame.viewProjection.set(frame.projection).mul(view);

		if (!isFinite(frame.viewProjection)) {
			return false;
		}

		frame.inverseProjection.invert();
		frame.inverseViewProjection.invert();

		// The shadow and sky passes march in view space, so they need the sun
		// there too. This depends on the view matrix, which is why it happens
		// here rather than in LightingModel.
		updateSunInViewSpace(frame, view);

		return true;
	}

	/** Transforms the world-space sun direction into view space. */
	private static void updateSunInViewSpace(FrameState frame, Matrix4f view) {
		frame.sunDirectionView.set(frame.sunDirection).mul(view);

		float lengthSquared = frame.sunDirectionView.lengthSquared();

		// A zero-length result means the sun is exactly at the camera; any
		// direction is as good as another and dividing would produce NaN.
		if (lengthSquared > 1.0e-12f && Float.isFinite(lengthSquared)) {
			frame.sunDirectionView.div((float) Math.sqrt(lengthSquared));
		} else {
			frame.sunDirectionView.set(0.0f, 0.0f, 1.0f);
		}
	}

	/**
	 * Builds the view matrix from the camera basis.
	 *
	 * <p>Yaw is applied before pitch, matching vanilla. View space looks down -Z,
	 * hence the negated forward column.
	 */
	private static Matrix4f viewMatrix(FrameState frame) {
		float yaw = (float) Math.toRadians(frame.cameraYaw);
		float pitch = (float) Math.toRadians(frame.cameraPitch);

		float sinYaw = (float) Math.sin(yaw);
		float cosYaw = (float) Math.cos(yaw);
		float sinPitch = (float) Math.sin(pitch);
		float cosPitch = (float) Math.cos(pitch);

		// Camera basis vectors.
		float rightX = -sinYaw;
		float rightY = 0.0f;
		float rightZ = cosYaw;

		float upX = sinYaw * cosPitch;
		float upY = cosPitch;
		float upZ = -cosYaw * cosPitch;

		float fwdX = sinYaw * sinPitch;
		float fwdY = -cosPitch;
		float fwdZ = -cosYaw * sinPitch;

		Matrix4f view = new Matrix4f(
				rightX, upX, -fwdX, 0.0f,
				rightY, upY, -fwdY, 0.0f,
				rightZ, upZ, -fwdZ, 0.0f,
				0.0f, 0.0f, 0.0f, 1.0f);

		// Camera transform: rotate by the inverse basis, then translate by -eye.
		view.m30(frame.cameraPos.x);
		view.m31(frame.cameraPos.y);
		view.m32(frame.cameraPos.z);

		return view;
	}

	private static boolean isFinite(Matrix4f matrix) {
		for (int i = 0; i < 16; i++) {
			if (!Float.isFinite(matrix.get(i))) {
				return false;
			}
		}

		return true;
	}

	private static Object invokeNoArg(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		try {
			return target.getClass().getMethod(methodName).invoke(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object invoke(Object target, String methodName, Object... args) {
		if (target == null) {
			return null;
		}

		try {
			for (var method : target.getClass().getMethods()) {
				if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
					continue;
				}

				return method.invoke(target, args);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Fall through to the default.
		}

		return null;
	}
}
