package dev.onigiri.ui;

import java.util.List;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.onigiri.OnigiriClient;

/**
 * Adds an "Onigiri..." button to Minecraft's own Options screen - the menu with
 * Video, Controls, Language and the rest - rather than burying it one level
 * deeper inside Video Settings.
 *
 * <p>Uses Fabric's {@code ScreenEvents.AFTER_INIT} rather than a mixin, so the
 * mod stays mixin-free. The event fires after every {@code init()}, including
 * the ones caused by a window resize, and the button is re-added idempotently
 * (any previous one is removed first) so it never duplicates or drifts.
 *
 * <p>Positioning anchors to the existing Done button instead of assuming a
 * layout: the button goes to the left of Done when there is room, to the right
 * when there is not, and centred above it as a last resort. {@code OptionsScreen}
 * extends {@code Screen} directly with no layout object to join, and
 * {@code Screen.addRenderableWidget} is protected, so the button is added
 * through Fabric's {@link Screens#getWidgets} list, which is the supported
 * route for touching a foreign screen.
 */
public final class OptionsScreenHook {
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;

	private static final Component LABEL = Component.literal("Onigiri...");
	private static final Component DONE = Component.translatable("gui.done");

	private static boolean logged;

	private OptionsScreenHook() {
	}

	/** Registers the hook. Called once from the client entrypoint. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// 26.3 keeps the options screens in an `options` subpackage.
			if (!(screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen)) {
				return;
			}

			addButton(screen, client);
		});
	}

	@SuppressWarnings("unchecked")
	private static void addButton(Screen screen, Minecraft client) {
		List<Object> widgets;

		try {
			widgets = (List<Object>) (List<?>) Screens.getWidgets(screen);
		} catch (RuntimeException | LinkageError e) {
			OnigiriClient.LOGGER.warn("Could not read the Options widget list: {}", e.toString());
			return;
		}

		try {
			// Idempotent: AFTER_INIT fires on every init, including resizes.
			widgets.removeIf(widget -> widget instanceof Button button && isOurs(button));

			Button done = findDone(widgets);

			int x;
			int y;

			if (done != null && done.getX() - GAP - BUTTON_WIDTH >= 0) {
				x = done.getX() - GAP - BUTTON_WIDTH;
				y = done.getY();
			} else if (done != null
					&& done.getX() + done.getWidth() + GAP + BUTTON_WIDTH <= screen.width) {
				x = done.getX() + done.getWidth() + GAP;
				y = done.getY();
			} else if (done != null) {
				x = done.getX() + (done.getWidth() - BUTTON_WIDTH) / 2;
				y = Math.max(0, done.getY() - GAP - BUTTON_HEIGHT);
			} else {
				x = (screen.width - BUTTON_WIDTH) / 2;
				y = screen.height - BUTTON_HEIGHT - 6;
			}

			widgets.add(Button.builder(LABEL, pressed ->
							client.setScreenAndShow(new OnigiriScreen(screen)))
					.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build());

			if (!logged) {
				logged = true;
				OnigiriClient.LOGGER.info("Added the Onigiri button to the Options screen");
			}
		} catch (UnsupportedOperationException e) {
			OnigiriClient.LOGGER.warn("The Options widget list is not modifiable: {}", e.toString());
		} catch (RuntimeException | LinkageError e) {
			OnigiriClient.LOGGER.warn("Could not add the Onigiri button to Options: {}", e.toString());
		}
	}

	private static boolean isOurs(Button button) {
		return button.getMessage().getString().equals(LABEL.getString());
	}

	private static Button findDone(List<Object> widgets) {
		String done = DONE.getString();

		for (Object widget : widgets) {
			if (widget instanceof Button button
					&& button.getMessage().getString().equals(done)) {
				return button;
			}
		}

		return null;
	}
}