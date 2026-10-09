package dev.onigiri;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.lwjgl.sdl.SDLScancode;

import dev.onigiri.gl.GlCaps;
import dev.onigiri.pipeline.FrameState;
import dev.onigiri.pipeline.LightingModel;
import dev.onigiri.pipeline.OnigiriPipeline;
import dev.onigiri.pipeline.ProjectionModel;
import dev.onigiri.ui.OnigiriScreen;

/**
 * Mod entrypoint and render hook.
 *
 * <p>Onigiri hooks {@code LevelRenderEvents.END_MAIN}: it fires once the world
 * has been drawn into the main framebuffer but before the GUI. That is the only
 * point where both the colour and depth attachments are complete, and using the
 * Fabric event avoids any mixin into the renderer - the pipeline binds its own
 * framebuffers and restores GL state on the way out.
 *
 * <p>Built for Android. Minecraft 26.3 reaches GL through
 * {@code SDL_GL_LoadLibrary}, and on Android the library MobileGlues supplies is
 * a GLES implementation lowered onto Vulkan, so every shader here is GLSL ES
 * 3.00 and no call newer than GL 3.1 / GLES 3.1 is used.
 */
public class OnigiriClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("onigiri");
	public static final String MOD_ID = "onigiri";

	private static OnigiriClient instance;

	private OnigiriPipeline pipeline;
	private OnigiriConfig config;

	private KeyMapping menuKey;
	private KeyMapping toggleKey;

	private boolean failed;
	private boolean pipelineReady;

	@Override
	public void onInitializeClient() {
		instance = this;

		config = OnigiriConfig.load(FabricLoader.getInstance().getConfigDir());

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "category"));

		menuKey = OnigiriScreen.registerMenuKey();

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.onigiri.toggle",
				InputConstants.Type.KEYBOARD,
				SDLScancode.SDL_SCANCODE_F8,
				category));

		LevelRenderEvents.END_MAIN.register(this::onLevelRenderEnd);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

		LOGGER.info("Onigiri ready - F6 opens settings, F8 toggles the renderer");
	}

	private void onClientTick(Minecraft client) {
		// The menu key opens the settings screen. Opening it replaces the level
		// render for that frame, which is exactly the desired behaviour: no
		// post-processing behind the menu.
		//
		// In 26.3 the active screen lives on Gui rather than on Minecraft, so
		// there is no Minecraft.getScreen(); client.gui.screen() is the accessor.
		while (menuKey.consumeClick()) {
			client.setScreenAndShow(new OnigiriScreen(client.gui.screen()));
		}

		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			config.save(FabricLoader.getInstance().getConfigDir());
			LOGGER.info("Onigiri {}", config.enabled ? "enabled" : "disabled");
		}
	}

	/**
	 * Renders one frame of the pipeline.
	 *
	 * <p>Every failure path here disables the mod and logs once. A post-process
	 * renderer that throws inside the render loop would take the whole client
	 * with it, so failing soft is the only acceptable behaviour - but it does
	 * log the reason and the renderer name, because on an unknown Android device
	 * "it stopped working" with no explanation is unactionable.
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
			LOGGER.error("Renderer was: {}", GlCaps.renderer());
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

		// Read once. This runs every frame, and the reflected lookup is cheap but
		// not free.
		float partialTick = ProjectionModel.partialTick(client);

		// Camera and lighting first: the matrices are built from the camera basis,
		// and the sun's view-space direction is derived from those matrices.
		LightingModel.update(frame, client, partialTick);

		// Builds the projection, view and view-projection together with their
		// inverses. This is the only place that happens.
		if (!ProjectionModel.update(frame, client, partialTick)) {
			return;
		}

		pipeline.render();
	}

	private void ensurePipeline(int width, int height) {
		if (pipeline == null) {
			pipeline = new OnigiriPipeline(config);
		}

		if (!pipelineReady) {
			GlCaps.probe();
			pipeline.initialise(width, height);
			pipelineReady = true;
			return;
		}

		pipeline.resize(width, height);
		pipeline.effectScaleChanged();
	}

	/**
	 * Releases GL resources when the game shuts down.
	 *
	 * <p>Without this, shaders, framebuffers and the VAO outlive the client. That
	 * matters on Android specifically: a launcher can keep the process alive
	 * between sessions, so the leak accumulates across launches rather than being
	 * reclaimed when the process finally exits.
	 */
	private void shutdown() {
		if (pipeline != null) {
			pipeline.close();
			pipeline = null;
		}

		pipelineReady = false;
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

	public boolean hasFailed() {
		return failed;
	}
}