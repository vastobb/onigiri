package dev.onigiri.gl;

import org.lwjgl.opengl.GL30;

/**
 * A colour texture plus the framebuffer that renders into it.
 *
 * <p>All of the effect targets are half resolution. On a phone that is the
 * single most valuable decision in the renderer: these effects are all
 * low-frequency signals, so halving each dimension cuts their fill cost to a
 * quarter while the temporal pass reconstructs apparent detail from history.
 * On a tile-based mobile GPU it also cuts the number of framebuffer tile
 * switches per frame, which costs more than the shading itself.
 *
 * <p>Storage is allocated with {@code glTexImage2D} rather than
 * {@code glTexStorage2D}. The latter is GL 4.2 / GLES 3.1, and MobileGlues
 * does not guarantee it; {@code glTexImage2D} is universal.
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
		int format = GlCaps.hdrFormat();

		framebufferId = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);

		colorTextureId = GL30.glGenTextures();
		GL30.glBindTexture(GL30.GL_TEXTURE_2D, colorTextureId);

		GL30.glTexImage2D(
				GL30.GL_TEXTURE_2D, 0, format, width, height, 0,
				GL30.GL_RGBA,
				format == GlCaps.FORMAT_RGBA16F ? GL30.GL_HALF_FLOAT : GL30.GL_UNSIGNED_BYTE,
				org.lwjgl.system.MemoryUtil.NULL);

		// A render target is always its own mip 0, so there is no mip chain to
		// build and nothing to gain from anisotropic filtering.
		GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR);
		GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);
		GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
		GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);

		GL30.glFramebufferTexture2D(
				GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_TEXTURE_2D, colorTextureId, 0);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			release();
			throw new IllegalStateException(
					"Incomplete framebuffer (" + width + "x" + height + "): 0x" + Integer.toHexString(status));
		}
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
		GL30.glViewport(0, 0, width, height);
	}

	private void release() {
		if (framebufferId != 0) {
			GL30.glDeleteFramebuffers(framebufferId);
			framebufferId = 0;
		}

		if (colorTextureId != 0) {
			GL30.glDeleteTextures(colorTextureId);
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