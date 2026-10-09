package dev.onigiri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import dev.onigiri.pipeline.FrameState;
import dev.onigiri.pipeline.LightingModel;
import dev.onigiri.pipeline.OnigiriPipeline;
import dev.onigiri.pipeline.ProjectionModel;

/**
 * Mod entrypoint and render hook.
 *
 * <p>Onigiri hooks {@code LevelRenderEvents.END_MAIN}: it fires once the world
 * has been drawn into the main framebuffer but before the GUI. That is the only
 * point where both the colour and depth attachments are complete, and using the
 * Fabric event avoids any mixin into the renderer - the pipeline binds its own
 * framebuffers and restores GL state on the way out.
 */
public class OnigiriClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("onigiri");
	public static final String MOD_ID = "onigiri";

	private static OnigiriClient instance;

	private OnigiriPipeline pipeline;
	private OnigiriConfig config;

	private KeyMapping toggleKey;
	private KeyMapping debugKey;

	private boolean failed;
	private boolean pipelineReady;

	@Override
	public void onInitializeClient() {
		instance = this;

		config = OnigiriConfig.load(FabricLoader.getInstance().getConfigDir());

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "category"));

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.onigiri.toggle",
				InputConstants.Type.KEYSYM,
				org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_F8,
				category));

		debugKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.onigiri.debug",
				InputConstants.Type.KEYSYM,
				org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_F9,
				category));

		LevelRenderEvents.END_MAIN.register(this::onLevelRenderEnd);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

		LOGGER.info("Onigiri ready - F8 toggles, F9 shows debug stats");
	}

	private void onClientTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			config.save(FabricLoader.getInstance().getConfigDir());
			LOGGER.info("Onigiri {}", config.enabled ? "enabled" : "disabled");
		}

		while (debugKey.consumeClick()) {
			config.debugOverlay = !config.debugOverlay;
			config.save(FabricLoader.getInstance().getConfigDir());
		}
	}

	/**
	 * Renders one frame of the pipeline.
	 *
	 * <p>Every failure path here disables the mod and logs once. A post-process
	 * renderer that throws inside the render loop would take the whole client
	 * with it, so failing soft is the only acceptable behaviour here.
	 */
	private void onLevelRenderEnd(LevelRenderContext context) {
		if (failed || !config.enabled) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client.level == null || client.getWindow() == null) {
			return;
		}

		try {
			renderFrame(client);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			LOGGER.error("Onigiri hit an unrecoverable error and is now disabled", e);
		}
	}

	private void renderFrame(Minecraft client) {
		int width = client.getWindow().getWidth();
		int height = client.getWindow().getHeight();

		if (width <= 0 || height <= 0) {
			return;
		}

		ensurePipeline(width, height);

		if (!pipelineReady) {
			return;
		}

		FrameState frame = pipeline.frame();

		// Camera and lighting first: the matrices are built from the camera basis,
		// and the sun's view-space direction is derived from those matrices.
		LightingModel.update(frame, client, partialTick(client));

		if (!ProjectionModel.update(frame, client)) {
			return;
		}

		pipeline.render();
	}

	private void ensurePipeline(int width, int height) {
		if (pipeline == null) {
			pipeline = new OnigiriPipeline(config);
		}

		if (!pipelineReady) {
			pipeline.initialise(width, height);
			pipelineReady = true;
			return;
		}

		pipeline.resize(width, height);
		pipeline.effectScaleChanged();
	}

	/**
	 * Reads the render interpolation factor.
	 *
	 * <p>Vanilla's partial tick lives behind a different accessor on each
	 * version. Falling back to zero costs a little smoothness in the sun's motion
	 * and nothing else, so there is no reason to risk a hard failure here.
	 */
	private static float partialTick(Minecraft client) {
		try {
			var tracker = client.getDeltaTracker();

			for (var method : tracker.getClass().getMethods()) {
				if (method.getParameterCount() != 0) {
					continue;
				}

				var name = method.getName();

				if (name.equals("getGameTimeDeltaPartialTick") || name.equals("getGameTimeDeltaPartialTicks")) {
					Object result = method.invoke(tracker);

					if (result instanceof Float f) {
						return f;
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Fall through to zero.
		}

		return 0.0f;
	}

	public static OnigiriClient instance() {
		return instance;
	}

	public OnigiriConfig config() {
		return config;
	}

	public OnigiriPipeline pipeline() {
		return pipeline;
	}

	public boolean isPipelineReady() {
		return pipelineReady;
	}
}
