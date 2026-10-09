package dev.onigiri.ui;

import java.lang.reflect.Field;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.onigiri.OnigiriClient;

/**
 * Adds an "Onigiri..." button to Minecraft's own Video Settings screen.
 *
 * <p>Reaching the game's options screen from a keybind-only mod is awkward: the
 * settings live two menus deep behind Pause > Options > Video, so a player who
 * wants to change a quality setting has to know the mod's hotkey. Putting the
 * entry where every other graphics option lives removes that friction.
 *
 * <p>Uses Fabric's {@code ScreenEvents.AFTER_INIT} rather than a mixin, so the
 * mod stays mixin-free. The event fires after every {@code init()}, including the
 * ones caused by a window resize, so the button is re-added with correct bounds
 * rather than drifting.
 *
 * <p>The button goes into the screen's own {@link HeaderAndFooterLayout} footer
 * so it lines up with the vanilla Done button instead of overlapping the option
 * list. That layout field is private, so it is reached reflectively.
 */
public final class VideoSettingsHook {
	private static final int BUTTON_WIDTH = 110;
	private static final int BUTTON_HEIGHT = 20;

	private static boolean logged;

	private VideoSettingsHook() {
	}

	/** Registers the hook. Called once from the client entrypoint. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// 26.3 moved the options screens into an `options` subpackage; the old
			// VideoSettingsScreen name is gone.
			if (!(screen instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen)) {
				return;
			}

			addToFooter(screen, client);
		});
	}

	/**
	 * Adds an Onigiri button to the screen's footer layout.
	 *
	 * <p>There is deliberately no fallback here. {@code Screen.addRenderableWidget}
	 * is protected, so a foreign screen cannot be given a widget directly, and the
	 * layout is the only supported route. If it ever goes away the button is lost
	 * and the hotkey still works - which is the right way round.
	 */
	private static void addToFooter(Screen screen, net.minecraft.client.Minecraft client) {
		try {
			// Walk up to OptionsSubScreen, which declares the field. Using
			// getDeclaredField on the concrete class would not find it.
			Field field = findLayoutField(screen.getClass());

			if (field == null) {
				OnigiriClient.LOGGER.warn("Video Settings has no HeaderAndFooterLayout; skipping the Onigiri button");
				return;
			}

			field.setAccessible(true);
			Object layout = field.get(screen);

			if (!(layout instanceof HeaderAndFooterLayout footer)) {
				return;
			}

			footer.addToFooter(
					Button.builder(Component.literal("Onigiri..."), pressed ->
							client.setScreenAndShow(new OnigiriScreen(screen)))
							.bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
							.build(),
					settings -> settings.alignHorizontallyRight().paddingRight(4));

			// AFTER_INIT fires after the screen has already arranged its layout,
			// so a button added here is positioned at (0,0) and never placed
			// until something re-arranges. Without this call the button exists
			// but sits unpositioned - which is why Video Settings showed no
			// Onigiri entry even though the hook ran without errors.
			footer.arrangeElements();

			if (!logged) {
				logged = true;
				OnigiriClient.LOGGER.info("Added the Onigiri button to Video Settings");
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			OnigiriClient.LOGGER.warn("Could not add the Onigiri button to Video Settings: {}",
					e.toString());
		}
	}

	private static Field findLayoutField(Class<?> type) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField("layout");

				if (field.getType() == HeaderAndFooterLayout.class) {
					return field;
				}
			} catch (NoSuchFieldException e) {
				// Keep walking up.
			}
		}

		return null;
	}
}