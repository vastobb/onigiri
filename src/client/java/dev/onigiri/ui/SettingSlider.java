package dev.onigiri.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.onigiri.OnigiriClient;
import dev.onigiri.OnigiriConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A labelled slider for one continuous setting.
 *
 * <p>Written as a real slider rather than a cycling button, because exposure,
 * occlusion strength and temporal feedback are all continuous - stepping them
 * through a cycle means the value you want is usually not in the list, and on a
 * phone fiddling with a slider is far quicker than cycling.
 *
 * <p>The value is held here rather than read back from the widget, since
 * {@code AbstractSliderButton} exposes no getter in 26.3.
 */
final class SettingSlider extends AbstractSliderButton {
	private static final double EPSILON = 1.0e-4;

	private final double min;
	private final double max;
	private final Consumer<Double> apply;
	private final java.util.function.Function<Double, String> format;

	private final String name;

	SettingSlider(int x, int y, int width, int height, String name,
				  double min, double max, double value,
				  java.util.function.Function<Double, String> format,
				  Consumer<Double> apply) {
		super(x, y, width, height, Component.empty(), normalise(value, min, max));

		this.name = name;
		this.min = min;
		this.max = max;
		this.format = format;
		this.apply = apply;

		updateMessage();
	}

	/** Maps a value into the 0..1 range the widget actually stores. */
	private static double normalise(double value, double min, double max) {
		double range = max - min;
		return range <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min) / range));
	}

	/** The inverse: turns the widget's 0..1 back into the setting's own range. */
	private double denormalise(double widgetValue) {
		return min + Math.max(0.0, Math.min(1.0, widgetValue)) * (max - min);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(name + ": " + format.apply(denormalise(value))));
	}

	@Override
	protected void applyValue() {
		apply.accept(denormalise(value));
	}
}