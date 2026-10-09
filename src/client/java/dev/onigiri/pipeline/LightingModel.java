package dev.onigiri.pipeline;

import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Derives per-frame lighting inputs from the game state.
 *
 * <p>Sun direction and colour are recomputed every frame rather than read from
 * the renderer. Vanilla bakes its sky light into vertex colours during chunk
 * building, which means it cannot respond to time-of-day changes without a
 * full relight; recomputing here costs a few trig calls and gives smooth,
 * physically plausible lighting across a full day cycle.
 */
public final class LightingModel {
	private static final float DAY_LENGTH = 24000.0f;

	private LightingModel() {
	}

	/**
	 * Updates the sun direction, colours and camera basis in {@code frame}.
	 *
	 * @param partialTick render interpolation factor, so the sun moves smoothly
	 *                    instead of stepping at the tick rate
	 */
	public static void update(FrameState frame, Minecraft client, float partialTick) {
		if (client.level == null) {
			return;
		}

		float timeOfDay = (client.level.getGameTime() % DAY_LENGTH + partialTick) / DAY_LENGTH;

		// ---- sun direction --------------------------------------------------
		// Vanilla's celestial angle: 0 ticks is sunrise, 6000 noon, 12000
		// sunset, 18000 midnight. The slight Z tilt matches the game's arc.
		float celestialAngle = timeOfDay * 360.0f - 90.0f;

		float sin = (float) Math.cos(Math.toRadians(celestialAngle));
		float cos = (float) Math.sin(Math.toRadians(celestialAngle));

		frame.sunDirection.set(-sin, cos, 0.15f).normalize();

		float elevation = frame.sunDirection.y;

		// ---- key light -------------------------------------------------------
		// Smooth dawn and dusk rather than a linear ramp, so the horizon does not
		// snap between fully lit and fully dark.
		float dayFactor = smoothstep(-0.12f, 0.22f, elevation);
		float twilight = 1.0f - Math.min(1.0f, Math.abs(elevation) * 4.0f);
		boolean night = elevation < -0.05f;

		// Below the horizon the moon becomes the key light: same direction maths,
		// a fraction of the intensity, cool tint.
		float keyIntensity = night ? 0.055f : dayFactor * 1.9f;

		float tintR;
		float tintG;
		float tintB;

		if (night) {
			tintR = 0.42f;
			tintG = 0.52f;
			tintB = 0.78f;
		} else {
			// Warm at the horizon, neutral overhead.
			tintR = lerp(1.0f, 1.0f, twilight);
			tintG = lerp(0.96f, 0.62f, twilight);
			tintB = lerp(0.88f, 0.32f, twilight);
		}

		frame.sunColor.set(tintR * keyIntensity, tintG * keyIntensity, tintB * keyIntensity);

		// ---- sky and ambient --------------------------------------------------
		float skyR;
		float skyG;
		float skyB;

		if (night) {
			skyR = 0.030f;
			skyG = 0.040f;
			skyB = 0.075f;
		} else {
			// Blue by day, drifting orange-pink through twilight.
			skyR = lerp(0.42f, 0.72f, twilight);
			skyG = lerp(0.60f, 0.42f, twilight);
			skyB = lerp(0.92f, 0.34f, twilight);
		}

		frame.skyColor.set(skyR, skyG, skyB);

		// Ambient stays well below the sky value. If they came close the shadows
		// would fill in and the occlusion pass would stop reading at all.
		frame.ambientColor.set(skyR * 0.30f + 0.012f, skyG * 0.30f + 0.012f, skyB * 0.30f + 0.018f);

		// ---- camera ------------------------------------------------------------
		Entity camera = client.getCameraEntity();

		if (camera != null) {
			frame.cameraPos.set((float) camera.getX(), (float) camera.getY(), (float) camera.getZ());
			frame.cameraYaw = camera.getYRot();
			frame.cameraPitch = camera.getXRot();
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0f - 2.0f * t);
	}
}
