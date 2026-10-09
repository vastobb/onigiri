package dev.onigiri.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.sdl.SDLScancode;

import dev.onigiri.OnigiriClient;
import dev.onigiri.OnigiriConfig;

/**
 * The in-game settings menu, opened from the keybind.
 *
 * <p>Written entirely against the public GUI API - no mixins. Two 26.3 API
 * details matter here and both are easy to get wrong:
 *
 * <ul>
 *   <li>{@code Screen} no longer has a {@code render} method. The draw path is
 *       {@code extractRenderState(GuiGraphicsExtractor, int, int, float)}.
 *   <li>{@code CycleButton.Builder} has no {@code withInitialValue}; the current
 *       value is supplied through the {@code builder(Function, Supplier)}
 *       overload, where the supplier is the default <em>and</em> the value the
 *       button opens on.
 * </ul>
 *
 * <p>Layout is a single column centred horizontally and clamped to the screen
 * height, because the target is a phone: a menu that assumes one aspect ratio
 * puts its last buttons off-screen on the other.
 */
public final class OnigiriScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 22;
	private static final int SPACING = 4;
	private static final int TOP_MARGIN = 34;

	private static final List<String> QUALITY_NAMES = List.of("Potato", "Mobile", "Balanced", "Ultra");

	private final Screen parent;
	private final OnigiriConfig config;

	/**
	 * @param parent the screen to return to on close; may be null, which returns
	 *               to the game
	 */
	public OnigiriScreen(Screen parent) {
		super(Component.literal("Onigiri"));

		this.parent = parent;

		OnigiriClient client = OnigiriClient.instance();
		this.config = client != null && client.config() != null
				? client.config()
				: OnigiriConfig.get();
	}

	/**
	 * Registers the keybind that opens this menu. Called from the entrypoint.
	 *
	 * <p>The category is passed in rather than created here.
	 * {@code KeyMapping.Category.register} throws if the same identifier is
	 * registered twice, and a static initialiser in this class ran before the
	 * entrypoint's own registration - so owning it in exactly one place is the
	 * only way it stays registered exactly once.
	 */
	public static KeyMapping registerMenuKey(KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.onigiri.menu",
				InputConstants.Type.KEYBOARD,
				SDLScancode.SDL_SCANCODE_F6,
				category));
	}

	@Override
	protected void init() {
		int left = (width - BUTTON_WIDTH) / 2;
		int y = Math.max(TOP_MARGIN, height / 2 - 100);

		y = addChoice(left, y, Component.literal("Quality"),
				value -> Component.literal(value),
				QUALITY_NAMES,
				config::qualityName,
				value -> config.quality = QUALITY_NAMES.indexOf(value));

		y = addToggle(left, y, "Ambient occlusion",
				() -> config.ambientOcclusion,
				value -> config.ambientOcclusion = value);

		y = addToggle(left, y, "Shadows",
				() -> config.shadows,
				value -> config.shadows = value);

		y = addToggle(left, y, "Reflections",
				() -> config.reflections,
				value -> config.reflections = value);

		y = addToggle(left, y, "Half resolution",
				() -> config.halfResolution,
				value -> config.halfResolution = value);

		y = addChoice(left, y, Component.literal("Resolution scale"),
				value -> Component.literal(value),
				List.of("0.5x", "0.75x", "1.0x"),
				this::currentScaleLabel,
				value -> config.resolutionScale = parseScale(value));

		y = addChoice(left, y, Component.literal("Exposure"),
				value -> Component.literal(String.format("%.2f", value)),
				List.of(0.8f, 0.9f, 1.05f, 1.2f, 1.4f),
				() -> nearest(config.exposure, List.of(0.8f, 0.9f, 1.05f, 1.2f, 1.4f)),
				value -> config.exposure = value);

		y = addChoice(left, y, Component.literal("Temporal feedback"),
				value -> Component.literal(String.format("%.2f", value)),
				List.of(0.85f, 0.88f, 0.92f, 0.95f),
				() -> nearest(config.temporalFeedback, List.of(0.85f, 0.88f, 0.92f, 0.95f)),
				value -> config.temporalFeedback = value);

		if (y + BUTTON_HEIGHT <= height) {
			addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
					.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());
		}
	}

	private String currentScaleLabel() {
		return config.resolutionScale <= 0.55f ? "0.5x"
				: config.resolutionScale <= 0.8f ? "0.75x"
				: "1.0x";
	}

	private static float parseScale(String label) {
		return switch (label) {
			case "0.5x" -> 0.5f;
			case "0.75x" -> 0.75f;
			default -> 1.0f;
		};
	}

	/** Snaps a possibly hand-edited float to the nearest offered value. */
	private static <T> T nearest(float value, List<T> options) {
		T best = options.get(0);
		float bestDistance = Float.MAX_VALUE;

		for (T option : options) {
			float distance = Math.abs(((Number) option).floatValue() - value);

			if (distance < bestDistance) {
				bestDistance = distance;
				best = option;
			}
		}

		return best;
	}

	private <T> int addChoice(int left, int y, Component label,
							  Function<T, Component> display,
							  List<T> values,
							  Supplier<T> current,
							  Consumer<T> apply) {
		// Clamp rather than overflow: on a short screen the tail of the column is
		// dropped instead of stacking back over the top of it.
		if (y + BUTTON_HEIGHT > height) {
			return y;
		}

		addRenderableWidget(CycleButton.builder(display, current)
				.withValues(values)
				.displayOnlyValue()
				.create(left, y, BUTTON_WIDTH, BUTTON_HEIGHT, label, (button, value) -> {
					apply.accept(value);
					commit();
				}));

		return y + BUTTON_HEIGHT + SPACING;
	}

	private int addToggle(int left, int y, String label,
						  BooleanSupplier getter,
						  Consumer<Boolean> setter) {
		List<Boolean> values = List.of(Boolean.FALSE, Boolean.TRUE);

		return addChoice(left, y, Component.literal(label),
				value -> Component.literal(value ? "On" : "Off"),
				values,
				getter::getAsBoolean,
				value -> setter.accept(value));
	}

	/**
	 * Writes the config and lets the pipeline resize its effect buffers.
	 *
	 * <p>Applied on every change rather than on a Save button so the effect of a
	 * setting is visible immediately - the alternative is a menu that looks like
	 * it is doing nothing until it closes.
	 */
	private void commit() {
		config.sanitise();
		config.save(FabricLoader.getInstance().getConfigDir());

		OnigiriClient client = OnigiriClient.instance();

		if (client != null && client.pipeline() != null) {
			client.pipeline().effectScaleChanged();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int left = (width - BUTTON_WIDTH) / 2;
		int top = Math.max(TOP_MARGIN, height / 2 - 100);

		graphics.centeredText(font, title, width / 2, Math.max(10, top - 22), 0xFFFFFFFF);

		// Show the buffer sizes the pipeline actually settled on, not the setting
		// that was picked, so a clamped or unsupported combination is visible.
		OnigiriClient client = OnigiriClient.instance();
		String status = client != null && client.pipeline() != null && client.pipeline().isInitialised()
				? client.pipeline().effectWidth() + "x" + client.pipeline().effectHeight() + " effect buffers"
				: "renderer not initialised";

		graphics.centeredText(font, Component.literal(status), width / 2, height - 16, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		commit();
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}