package dev.onigiri.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

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

			GpuTexture colourTex = main.getColorTexture();
			GpuTexture depthTex = main.hasDepth() ? main.getDepthTexture() : null;

			logTargetInfoOnce(colourTex, depthTex);

			int colour = glId(colourTex);

			if (colour == 0) {
				return null;
			}

			int depth = glId(depthTex);

			if (depth == 0) {
				return null;
			}

			return new int[] { colour, depth };
		} catch (RuntimeException | LinkageError e) {
			OnigiriClient.LOGGER.warn("Could not resolve the game's render target: {}", e.toString());
			return null;
		}
	}

	private static boolean infoLogged;

	/**
	 * Logs the size, format and backend type of the game textures, once.
	 *
	 * <p>There when the next "it renders nothing" report arrives: whether the
	 * target is an unexpected size, an unexpected format, or not a GL texture
	 * at all is the difference between three different fixes.
	 */
	private static void logTargetInfoOnce(GpuTexture colour, GpuTexture depth) {
		if (infoLogged) {
			return;
		}

		infoLogged = true;

		OnigiriClient.LOGGER.info("Game target: colour {}, depth {}",
				describe(colour), describe(depth));
	}

	private static String describe(GpuTexture texture) {
		if (texture == null) {
			return "absent";
		}

		try {
			return texture.getWidth(0) + "x" + texture.getHeight(0)
					+ " " + texture.getFormat()
					+ " " + texture.getClass().getSimpleName()
					+ (texture.isClosed() ? " CLOSED" : "");
		} catch (RuntimeException | LinkageError e) {
			return "unreadable (" + e + ")";
		}
	}

	/**
	 * Verifies that both game textures can actually be sampled as 2D textures,
	 * repairing what can be repaired.
	 *
	 * <p>Two states produce a black screen with no error anywhere, and both are
	 * checked here:
	 *
	 * <ul>
	 *   <li>The texture was created with a different target (array, multisample)
	 *       and cannot bind as {@code TEXTURE_2D}. Sampling it then returns black
	 *       forever. Nothing can be done, so this returns false and the frame is
	 *       skipped - vanilla output beats a black screen.
	 *   <li>The min filter is a mipmapped mode on a texture with no mip chain, or
	 *       the compare mode is set. Either makes the texture incomplete for plain
	 *       sampling, which also reads back as black. Both are safe to reset: the
	 *       game samples through its own sampler objects, so the texture's own
	 *       parameters only affect this mod's raw sampling.
	 * </ul>
	 *
	 * @return true when both textures are sampleable
	 */
	public static boolean ensureSampleable(int colour, int depth) {
		return sampleable(colour, "colour") && sampleable(depth, "depth");
	}

	private static boolean sampleable(int id, String label) {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
		int bindError = GL11.glGetError();

		if (bindError != GL11.GL_NO_ERROR) {
			OnigiriClient.LOGGER.warn(
					"Game {} texture {} cannot bind as TEXTURE_2D (GL error 0x{}); "
							+ "it uses a different target and cannot be sampled. Skipping post-processing.",
					label, id, Integer.toHexString(bindError));
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
			return false;
		}

		int min = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);

		// 0x2700-0x2703 are the mipmapped min filters. A texture with no mip chain
		// sampled under one of these is incomplete and reads back as black.
		if (min >= 0x2700 && min <= 0x2703) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			OnigiriClient.LOGGER.info(
					"Game {} texture used a mipmapped min filter (0x{}); set to LINEAR for sampling.",
					label, Integer.toHexString(min));
		}

		int compare = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE);

		if (compare != GL11.GL_NONE) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
			OnigiriClient.LOGGER.info(
					"Game {} texture had depth-compare enabled (0x{}); disabled for sampling.",
					label, Integer.toHexString(compare));
		}

		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		return true;
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