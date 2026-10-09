package dev.onigiri;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Onigiri's tunables, persisted as JSON next to the game's other mod configs.
 *
 * <p>Every field is a plain value with a sensible default so a missing or
 * partially-written file still yields a usable configuration. Quality presets
 * exist mainly to change the two things that actually move the frame time:
 * sample counts and the temporal feedback.
 */
public final class OnigiriConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// --- quality -----------------------------------------------------------

	/** 0 = potato, 1 = balanced, 2 = high, 3 = ultra. */
	public int quality = 2;

	/** Master switch for the whole pipeline. */
	public boolean enabled = true;

	/** Render the effects at half resolution. Disabling this doubles the cost. */
	public boolean halfResolution = true;

	// --- effects -----------------------------------------------------------

	public boolean ambientOcclusion = true;
	public boolean reflections = true;
	public boolean shadows = true;

	public float aoStrength = 0.85f;
	public float ssrStrength = 1.0f;
	public float shadowStrength = 0.9f;

	public float specularStrength = 0.6f;
	public float exposure = 1.05f;
	public float vignette = 0.18f;
	public float saturation = 1.08f;
	public float nightLift = 0.015f;

	// --- temporal ----------------------------------------------------------

	/**
	 * How much history the temporal pass trusts, 0..1. Higher is smoother and
	 * lags more behind fast camera motion.
	 */
	public float temporalFeedback = 0.92f;

	// --- debug -------------------------------------------------------------

	public boolean debugOverlay = false;

	private static OnigiriConfig instance = new OnigiriConfig();

	public static OnigiriConfig get() {
		return instance;
	}

	/** Loads from {@code config/onigiri.json}, falling back to defaults. */
	public static OnigiriConfig load(Path configDir) {
		Path file = configDir.resolve("onigiri.json");

		if (!Files.exists(file)) {
			instance = new OnigiriConfig();
			instance.save(configDir);
			return instance;
		}

		try (Reader reader = Files.newBufferedReader(file)) {
			OnigiriConfig loaded = GSON.fromJson(reader, OnigiriConfig.class);
			instance = loaded == null ? new OnigiriConfig() : loaded;
			instance.sanitise();
			return instance;
		} catch (IOException | RuntimeException e) {
			// A corrupt config should never stop the game from starting.
			instance = new OnigiriConfig();
			return instance;
		}
	}

	public void save(Path configDir) {
		Path file = configDir.resolve("onigiri.json");

		try {
			Files.createDirectories(configDir);

			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			OnigiriClient.LOGGER.warn("Could not write Onigiri config to {}", file, e);
		}
	}

	/** Clamps values into sane ranges after loading an edited file. */
	public void sanitise() {
		quality = clamp(quality, 0, 3);

		aoStrength = clamp(aoStrength, 0.0f, 1.0f);
		ssrStrength = clamp(ssrStrength, 0.0f, 2.0f);
		shadowStrength = clamp(shadowStrength, 0.0f, 1.0f);
		specularStrength = clamp(specularStrength, 0.0f, 2.0f);
		exposure = clamp(exposure, 0.1f, 3.0f);
		vignette = clamp(vignette, 0.0f, 1.0f);
		saturation = clamp(saturation, 0.0f, 2.0f);
		nightLift = clamp(nightLift, 0.0f, 0.2f);
		temporalFeedback = clamp(temporalFeedback, 0.5f, 0.97f);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	// --- presets -----------------------------------------------------------

	/** Horizon slices per pixel for the AO pass. */
	public int aoDirections() {
		return switch (quality) {
			case 0 -> 2;
			case 1 -> 3;
			case 2 -> 4;
			default -> 6;
		};
	}

	/** Taps per AO slice. */
	public int aoSteps() {
		return switch (quality) {
			case 0 -> 2;
			case 1 -> 4;
			case 2 -> 5;
			default -> 8;
		};
	}

	/** Ray steps for the reflection pass. */
	public int ssrSteps() {
		return switch (quality) {
			case 0 -> 12;
			case 1 -> 20;
			case 2 -> 28;
			default -> 40;
		};
	}

	/** Ray steps for the shadow pass. Only a quarter of pixels run it per frame. */
	public int shadowSteps() {
		return switch (quality) {
			case 0 -> 6;
			case 1 -> 10;
			case 2 -> 14;
			default -> 20;
		};
	}

	/**
	 * Fraction of pixels that trace shadows each frame. Kept at 0.25 because the
	 * temporal pass resolves it over four frames; lower values ghost on fast
	 * camera motion.
	 */
	public float shadowActiveFraction() {
		return 0.25f;
	}
}
