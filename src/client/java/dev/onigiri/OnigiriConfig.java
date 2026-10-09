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
 * <p>Every field has a default, so a missing or partially-written file still
 * yields a usable configuration.
 *
 * <p>Defaults are chosen for a phone rather than a desktop GPU. A mid-range
 * Adreno or Mali cannot afford the desktop preset's sample counts at 1080p, so
 * quality starts at 1 and reflections default to off - SSR is the single most
 * expensive pass, and a player on a phone is far better served by shadows and AO
 * that hold frame rate than by reflections that do not.
 */
public final class OnigiriConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// --- quality -----------------------------------------------------------

	/** 0 = potato, 1 = mobile, 2 = balanced, 3 = ultra. */
	public int quality = 1;

	/** Master switch for the whole pipeline. */
	public boolean enabled = true;

	/**
	 * Render the effects at half resolution.
	 *
	 * <p>Leave this on. Disabling it quadruples the fill cost of five of the six
	 * passes, which on a phone is the difference between playable and not.
	 */
	public boolean halfResolution = true;

	/**
	 * Scale applied on top of {@link #halfResolution}.
	 *
	 * <p>An extra 0.5 takes the effects to quarter resolution, which is a further
	 * fourfold saving in the chain. It is the single most effective knob on a
	 * weak GPU, and the upsampler is designed to hide it.
	 */
	public float resolutionScale = 1.0f;

	// --- effects -----------------------------------------------------------

	public boolean ambientOcclusion = true;

	/** Off by default: SSR is the most expensive pass in the chain. */
	public boolean reflections = false;

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

	/** Draws the resolution and effect-state readout on the debug overlay. */
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

		resolutionScale = clamp(resolutionScale, 0.5f, 1.0f);

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

	/** Human-readable name for the current quality tier. */
	public String qualityName() {
		return switch (quality) {
			case 0 -> "Potato";
			case 1 -> "Mobile";
			case 2 -> "Balanced";
			default -> "Ultra";
		};
	}

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
			case 1 -> 3;
			case 2 -> 5;
			default -> 8;
		};
	}

	/** Ray steps for the reflection pass. */
	public int ssrSteps() {
		return switch (quality) {
			case 0 -> 8;
			case 1 -> 14;
			case 2 -> 28;
			default -> 40;
		};
	}

	/** Ray steps for the shadow pass. Only a quarter of pixels run it per frame. */
	public int shadowSteps() {
		return switch (quality) {
			case 0 -> 4;
			case 1 -> 8;
			case 2 -> 14;
			default -> 20;
		};
	}

	/**
	 * Fraction of pixels that trace shadows each frame.
	 *
	 * <p>Held at 0.25 because the temporal pass resolves it over four frames;
	 * lowering it ghosts on fast camera motion, which on a phone is most of the
	 * time. The shader hardcodes the same 0.25, so this is the single place the
	 * schedule is described.
	 */
	public float shadowActiveFraction() {
		return 0.25f;
	}
}