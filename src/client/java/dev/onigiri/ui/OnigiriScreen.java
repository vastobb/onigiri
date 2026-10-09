package dev.onigiri.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.sdl.SDLScancode;

import dev.onigiri.OnigiriClient;
import dev.onigiri.OnigiriConfig;

/**
 * The in-game settings menu, opened from the keybind or from Video Settings.
 *
 * <p>Built on the same {@link HeaderAndFooterLayout} that Minecraft's own
 * settings screens use, so it inherits their title header, spacing and Done
 * footer rather than hand-placing every button. That is what makes it look like a
 * vanilla screen instead of a mod screen.
 *
 * <p>Two 26.3 API details that are easy to get wrong and were verified against
 * the jar rather than assumed:
 *
 * <ul>
 *   <li>{@code Screen} has no {@code render} method; the draw path is
 *       {@code extractRenderState(GuiGraphicsExtractor, int, int, float)}.
 *   <li>{@code CycleButton.Builder} has no {@code withInitialValue}; the current
 *       value comes through the {@code builder(Function, Supplier)} overload.
 * </ul>
 *
 * <p>Continuous settings use real sliders and everything applies immediately, so
 * the effect of a change is visible while the menu is still open.
 */
public final class OnigiriScreen extends Screen {
	private static final int CONTENT_WIDTH = 260;
	private static final int ROW_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int SECTION_GAP = 10;

	private static final List<String> QUALITY_NAMES = List.of("Potato", "Mobile", "Balanced", "Ultra");
	private static final List<String> SCALE_NAMES = List.of("0.5x", "0.75x", "1.0x");

	private final Screen parent;
	private final OnigiriConfig config;
	private final HeaderAndFooterLayout layout;

	public OnigiriScreen(Screen parent) {
		super(Component.literal("Onigiri"));

		this.parent = parent;
		this.layout = new HeaderAndFooterLayout(this);

		OnigiriClient client = OnigiriClient.instance();
		this.config = client != null && client.config() != null
				? client.config()
				: OnigiriConfig.get();
	}

	/**
	 * Registers the keybind that opens this menu. Called from the entrypoint.
	 *
	 * <p>The category is passed in rather than created here:
	 * {@code KeyMapping.Category.register} throws if the same identifier is
	 * registered twice, and owning it in one place is the only way it stays
	 * registered exactly once.
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
		// Title header, with a live status line under it so it is obvious whether
		// the renderer is actually running.
		layout.addTitleHeader(title, font);

		int left = (width - CONTENT_WIDTH) / 2;
		int y = 50;

		y = section(left, y, "EFFECTS");
		y = toggle(left, y, "Ambient occlusion", () -> config.ambientOcclusion,
				v -> config.ambientOcclusion = v);
		y = toggle(left, y, "Sun shadows", () -> config.shadows,
				v -> config.shadows = v);
		y = toggle(left, y, "Reflections", () -> config.reflections,
				v -> config.reflections = v);
		y = toggle(left, y, "Specular highlights", () -> config.specularStrength > 0.001f,
				v -> config.specularStrength = v ? 0.6f : 0.0f);

		y += SECTION_GAP;
		y = section(left, y, "PERFORMANCE");
		y = choice(left, y, "Quality", QUALITY_NAMES, config::qualityName,
				v -> config.quality = QUALITY_NAMES.indexOf(v));
		y = choice(left, y, "Effect resolution", SCALE_NAMES, this::currentScale,
				v -> config.resolutionScale = parseScale(v));
		y = toggle(left, y, "Half resolution", () -> config.halfResolution,
				v -> config.halfResolution = v);
		y = slider(left, y, "Exposure", 0.6, 1.6, config.exposure,
				v -> String.format("%.2f", v), v -> config.exposure = v.floatValue());
		y = slider(left, y, "Temporal feedback", 0.5, 0.97, config.temporalFeedback,
				v -> String.format("%.2f", v), v -> config.temporalFeedback = v.floatValue());

		y += SECTION_GAP;
		y = section(left, y, "INTENSITY");
		y = slider(left, y, "Occlusion strength", 0.0, 1.0, config.aoStrength,
				v -> String.format("%.2f", v), v -> config.aoStrength = v.floatValue());
		y = slider(left, y, "Shadow strength", 0.0, 1.0, config.shadowStrength,
				v -> String.format("%.2f", v), v -> config.shadowStrength = v.floatValue());
		y = slider(left, y, "Reflection strength", 0.0, 1.5, config.ssrStrength,
				v -> String.format("%.2f", v), v -> config.ssrStrength = v.floatValue());
		y = slider(left, y, "Saturation", 0.0, 1.6, config.saturation,
				v -> String.format("%.2f", v), v -> config.saturation = v.floatValue());

		layout.addToFooter(Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(0, 0, 150, ROW_HEIGHT)
				.build(), settings -> settings.alignHorizontallyCenter());
	}

	private String currentScale() {
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

	/** A dim section heading, laid out as a real element so it flows with the rest. */
	private int section(int left, int y, String text) {
		StringWidget heading = new StringWidget(left, y,
				Component.literal(text).withStyle(ChatFormatting.GRAY), font);

		heading.setMaxWidth(CONTENT_WIDTH);
		layout.addToContents(heading);

		return y + ROW_HEIGHT - 6;
	}

	private int choice(int left, int y, String label, List<String> values,
					   Supplier<String> current, Consumer<String> apply) {
		// The type witness is required, not decorative: CycleButton declares
		// builder(Function, Supplier<T>) and builder(Function, T), and with T
		// unbounded the second one can itself bind to Supplier<String>, so javac
		// cannot choose between them without being told.
		layout.addToContents(CycleButton.<String>builder(value -> Component.literal(value), current)
				.withValues(values)
				.displayOnlyValue()
				.create(left, y, CONTENT_WIDTH, ROW_HEIGHT,
						Component.literal(label),
						(button, value) -> {
							apply.accept(value);
							commit();
						}));

		return y + ROW_HEIGHT + GAP;
	}

	private int toggle(int left, int y, String label,
					   BooleanSupplier getter, Consumer<Boolean> setter) {
		List<Boolean> values = List.of(Boolean.FALSE, Boolean.TRUE);

		layout.addToContents(CycleButton.<Boolean>builder(
						on -> Component.literal(on ? "On" : "Off").withStyle(
								on ? ChatFormatting.GREEN : ChatFormatting.GRAY),
						getter::getAsBoolean)
				.withValues(values)
				.displayOnlyValue()
				.create(left, y, CONTENT_WIDTH, ROW_HEIGHT,
						Component.literal(label),
						(button, value) -> {
							setter.accept(value);
							commit();
						}));

		return y + ROW_HEIGHT + GAP;
	}

	private int slider(int left, int y, String label, double min, double max, double value,
					   Function<Double, String> format, Consumer<Double> apply) {
		layout.addToContents(new SettingSlider(left, y, CONTENT_WIDTH, ROW_HEIGHT, label,
				min, max, value, format, apply::accept), settings -> settings.paddingBottom(0));

		return y + ROW_HEIGHT + GAP;
	}

	/**
	 * Writes the config and lets the pipeline resize its effect buffers.
	 *
	 * <p>Applied on every change rather than behind a Save button so the effect of
	 * a setting is visible immediately.
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

		OnigiriClient client = OnigiriClient.instance();
		int y = height - 46;

		if (client == null) {
			return;
		}

		if (client.hasFailed()) {
			graphics.centeredText(font,
					Component.literal("Renderer disabled - see the log").withStyle(ChatFormatting.RED),
					width / 2, y, 0xFFFFFFFF);
			return;
		}

		// Show the buffer sizes the pipeline actually settled on, not the setting
		// that was picked, so a clamped or unsupported combination is visible.
		String status = client.pipeline() != null && client.pipeline().isInitialised()
				? client.pipeline().effectWidth() + " x " + client.pipeline().effectHeight() + " effect buffers"
				: "renderer not initialised";

		Component line = Component.literal(status).withStyle(ChatFormatting.DARK_GRAY);

		if (client.consecutiveFailures() > 0) {
			line = Component.literal(status + "  (" + client.consecutiveFailures() + " frame errors)")
					.withStyle(ChatFormatting.RED);
		}

		graphics.centeredText(font, line, width / 2, y, 0xFFFFFFFF);
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