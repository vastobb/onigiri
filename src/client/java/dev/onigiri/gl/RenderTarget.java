package dev.onigiri.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * A colour texture plus the framebuffer that renders into it.
 *
 * <p>The effects chain allocates these at half resolution. That is the single
 * largest performance decision in the renderer: ambient occlusion, reflections
 * and shadows are all low-frequency signals, and the temporal pass reconstructs
 * full apparent detail from the half-res history regardless.
 *
 * <p>Colour is {@code RGBA16F}. Several passes legitimately produce values above
 * 1.0 before the tonemap, so an 8-bit intermediate would band in exactly the
 * highlights that matter most.
 */
public final class RenderTarget implements AutoCloseable {
	private int width;
	private int height;

	private int framebufferId;
	private int colorTextureId;
	private boolean disposed;

	public RenderTarget(int width, int height) {
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		allocate();
	}

	/**
	 * Reallocates at a new size if the requested dimensions differ.
	 *
	 * @return {@code true} when a reallocation occurred, which the caller treats
	 *         as the signal to drop its temporal history
	 */
	public boolean resize(int newWidth, int newHeight) {
		int w = Math.max(1, newWidth);
		int h = Math.max(1, newHeight);

		if (w == width && h == height) {
			return false;
		}

		width = w;
		height = h;
		release();
		allocate();
		return true;
	}

	private void allocate() {
		framebufferId = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);

		colorTextureId = GL13.glGenTextures();
		GL13.glBindTexture(GL13.GL_TEXTURE_2D, colorTextureId);

		// Immutable storage: one level, no mip chain, allocated once at creation.
		// A render target is always its own mip 0, so mipmaps would never be used.
		GL45.glTexStorage2D(GL45.GL_TEXTURE_2D, 1, GL45.GL_RGBA16F, width, height);

		GL11.glTexParameteri(GL13.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL13.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL13.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL13.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);

		GL30.glFramebufferTexture2D(
				GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL13.GL_TEXTURE_2D, colorTextureId, 0);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			release();
			throw new IllegalStateException(
					"Incomplete framebuffer (" + width + "x" + height + "): 0x" + Integer.toHexString(status));
		}

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
	}

	public int framebufferId() {
		return framebufferId;
	}

	public int colorTextureId() {
		return colorTextureId;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Binds this target for drawing and sets a matching viewport. */
	public void bindForDrawing() {
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
		GL11.glViewport(0, 0, width, height);
	}

	/** Copies {@code textureId} into this target's colour attachment. */

	private void release() {
		if (framebufferId != 0) {
			GL30.glDeleteFramebuffers(framebufferId);
			framebufferId = 0;
		}

		if (colorTextureId != 0) {
			GL13.glDeleteTextures(colorTextureId);
			colorTextureId = 0;
		}
	}

	@Override
	public void close() {
		if (!disposed) {
			disposed = true;
			release();
		}
	}
}
