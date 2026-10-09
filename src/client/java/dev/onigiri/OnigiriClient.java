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

import dev.onigiri.gl.GameTarget;
import dev.onigiri.gl.GlCaps;
import dev.onigiri.pipeline.FrameState;
import dev.onigiri.pipeline.LightingModel;
import dev.onigiri.pipeline.OnigiriPipeline;
import dev.onigiri.pipeline.ProjectionModel;
import dev.onigiri.ui.OnigiriScreen;
import dev.onigiri.ui.VideoSettingsHook;

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

	/** Consecutive bad frames tolerated before giving up for the session. */
	private static final int FAILURE_LIMIT = 60;

	private static OnigiriClient instance;

	private OnigiriPipeline pipeline;
	private OnigiriConfig config;

	private KeyMapping menuKey;
	private KeyMapping toggleKey;

	private boolean failed;
	private int consecutiveFailures;
	private boolean pipelineReady;

	@Override
	public void onInitializeClient() {
		instance = this;

		config = OnigiriConfig.load(FabricLoader.getInstance().getConfigDir());

		// Registered here exactly once. KeyMapping.Category.register throws on a
		// duplicate identifier, and the entrypoint is the only place that runs
		// before any screen is constructed.
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "category"));

		menuKey = OnigiriScreen.registerMenuKey(category);

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.onigiri.toggle",
				InputConstants.Type.KEYBOARD,
				SDLScancode.SDL_SCANCODE_F8,
				category));

		LevelRenderEvents.END_MAIN.register(this::onLevelRenderEnd);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

		// Also reachable from Options > Video, so the settings do not require
		// knowing the hotkey.
		VideoSettingsHook.register();

		// On a phone there may be no F6 key at all - Pojav exposes an on-screen
		// keyboard, and most sessions never open it. The hotkey is a convenience
		// for anyone with a bluetooth keyboard; the reliable route on a touch
		// device is Options > Video > Onigiri..., which is why that button exists.
		//
		// The active texture and attachment ids are logged on the first rendered
		// frame because the "no visible effect" failure is otherwise silent.
		LOGGER.info("Onigiri ready. F6 settings, F8 toggle (a bluetooth keyboard is needed for these; "
				+ "on a touch device use Options > Video > Onigiri...)");
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
	 * <p>A failure is logged and counted, but does not permanently disable the
	 * renderer on the first one. A post-process renderer that throws inside the
	 * render loop would take the whole client with it, so failing soft is the only
	 * acceptable behaviour - but latching off after a single bad frame hides the
	 * reason entirely, which is worse than the failure: a class of bug that only
	 * bites on frame one (a bad matrix cast, a context that is not ready yet) used
	 * to leave the game looking like plain vanilla with a single ERROR line
	 * nobody reads.
	 *
	 * <p>After {@link #FAILURE_LIMIT} consecutive failures the renderer gives up
	 * for the session, since that indicates something structural rather than
	 * transient, and says so on screen.
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
			renderFrame(client, context);
			consecutiveFailures = 0;
		} catch (RuntimeException | LinkageError e) {
			consecutiveFailures++;

			if (consecutiveFailures == 1) {
				LOGGER.error("Onigiri failed to render a frame: {}", e.toString());
				LOGGER.error("Renderer was: {}", GlCaps.renderer());
				LOGGER.error("Press F6 for settings. The renderer will retry.", e);
			} else if (consecutiveFailures >= FAILURE_LIMIT) {
				failed = true;
				LOGGER.error("Onigiri failed {} consecutive frames and is now disabled for this session",
						FAILURE_LIMIT);
				warnPlayer("Onigiri was disabled after repeated rendering errors. See the log.");
			}
		}
	}

	/** Shows a short message in chat, so a disabled renderer is not silent. */
	private void warnPlayer(String message) {
		try {
			Minecraft client = Minecraft.getInstance();

			// sendSystemMessage, not displayClientMessage: the latter with its
			// boolean overlay argument is gone in 26.3.
			if (client.player != null) {
				client.player.sendSystemMessage(
						net.minecraft.network.chat.Component.literal("Onigiri: " + message)
								.withStyle(net.minecraft.ChatFormatting.RED));
			}
		} catch (RuntimeException | LinkageError e) {
			// Chat is a courtesy; never let it turn into a second failure.
		}
	}

	private void renderFrame(Minecraft client, LevelRenderContext context) {
		int width = client.getWindow().getWidth();
		int height = client.getWindow().getHeight();

		if (width <= 0 || height <= 0) {
			return;
		}

		ensurePipeline(width, height);

		if (!pipelineReady) {
			return;
		}

		// The world's colour and depth come from the game's main render target,
		// not from the bound framebuffer: on this path the bound draw framebuffer
		// at END_MAIN is 0 while the world sits in GPU textures. Reading the bound
		// framebuffer was the entire "no shaders" report - every frame skipped.
		int[] game = GameTarget.resolve(context);

		if (game == null) {
			pipeline.frame().requestHistoryReset();
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

		pipeline.render(game[0], game[1]);
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

	/** Consecutive render failures so far; shown in the settings menu. */
	public int consecutiveFailures() {
		return consecutiveFailures;
	}
}