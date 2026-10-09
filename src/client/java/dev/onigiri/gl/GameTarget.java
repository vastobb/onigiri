package dev.onigiri.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import dev.onigiri.OnigiriClient;

/**
 * Resolves the GL texture ids of the world's colour and depth buffers.
 *
 * <p>On 26.x the world lives in the game's {@code MainTarget}, a pair of GPU
 * textures managed by the frame graph - not in whatever framebuffer happens to
 * be bound when a render event fires. The previous implementation read the
 * attachments of the bound framebuffer, which is correct on desktop GL where the
 * main target is bound for the whole pass, but on this path the bound draw
 * framebuffer at {@code END_MAIN} is 0 (the default framebuffer). Every frame
 * was therefore skipped as "not sampleable" and the game looked exactly like
 * vanilla, with a clean log. That was the entire "no shaders" report.
 *
 * <p>On the GL backend (which is what MobileGlues presents) a {@code GpuTexture}
 * is a {@code GlTexture} wrapping a real GL texture id, reachable through
 * {@code glId()}. If the backend ever stops being GL the cast fails and the
 * frame is skipped instead of crashing, which is the right way round.
 */
public final class GameTarget {
	private GameTarget() {
	}

	/**
	 * Returns the game's colour and depth texture ids as {@code {colour, depth}},
	 * or {@code null} when they cannot be resolved this frame.
	 */
	public static int[] resolve(LevelRenderContext context) {
		if (!(context instanceof AbstractLevelRenderContext render)) {
			return null;
		}

		try {
			RenderTarget main = render.gameRenderer().mainRenderTarget();

			if (main == null) {
				return null;
			}

			int colour = glId(main.getColorTexture());

			if (colour == 0) {
				return null;
			}

			int depth = main.hasDepth() ? glId(main.getDepthTexture()) : 0;

			if (depth == 0) {
				return null;
			}

			return new int[] { colour, depth };
		} catch (RuntimeException | LinkageError e) {
			OnigiriClient.LOGGER.warn("Could not resolve the game's render target: {}", e.toString());
			return null;
		}
	}

	/**
	 * The GL texture id behind a game texture, or 0 when it is not a GL texture.
	 */
	private static int glId(GpuTexture texture) {
		if (texture == null || texture.isClosed()) {
			return 0;
		}

		try {
			if (texture instanceof GlTexture gl) {
				return gl.glId();
			}
		} catch (RuntimeException | LinkageError e) {
			// Not a GL backend, or the handle is gone. Skip the frame.
		}

		return 0;
	}
}