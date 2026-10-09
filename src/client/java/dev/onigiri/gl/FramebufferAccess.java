package dev.onigiri.gl;

import java.nio.IntBuffer;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Reads the colour and depth textures Minecraft has already rendered into.
 *
 * <p>Two constraints shape this class.
 *
 * <p>First, on 26.x the game's target sits behind {@code MainTarget} and
 * {@code GpuTextureView}, abstractions over whichever backend is active. Reading
 * the attachment IDs straight out of OpenGL sidesteps that entirely, and works
 * the same on desktop GL and on MobileGlues' translated GLES.
 *
 * <p>Second, and more important on mobile: <strong>this runs twice per frame</strong>,
 * inside the render loop, and it used to drain the GL error queue both times.
 * {@code glGetError} is a synchronising call - on Adreno and Mali it flushes the
 * command buffer and costs a visible stall. Doing that twice a frame is enough
 * to lose the frame on its own.
 *
 * <p>So the error check is gone entirely and the scratch buffer is allocated
 * once. The attachment IDs are queried directly; a query that fails yields 0,
 * and the caller skips the frame, which is the correct outcome anyway.
 */
public final class FramebufferAccess {
	/** Reused across calls; framebuffer queries are on the hot path. */
	private static final IntBuffer SCRATCH = MemoryUtil.memAllocInt(4);

	private FramebufferAccess() {
	}

	/** Returns the texture attached to {@code attachment} of the currently bound
	 * framebuffer, or 0 when the slot is empty, is a renderbuffer, or the bound
	 * target is the default framebuffer.
	 *
	 * <p>A multisampled attachment has no single sampleable texture, so this
	 * returns 0 there too and the frame is skipped rather than sampling a
	 * resolve-in-progress buffer.
	 *
	 * @param attachment {@link GL30#GL_COLOR_ATTACHMENT0} or
	 *                   {@link GL30#GL_DEPTH_ATTACHMENT}
	 */
	public static int attachedTexture(int attachment) {
		int framebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

		if (framebuffer == 0) {
			// The default framebuffer has no addressable texture object.
			return 0;
		}

		SCRATCH.clear();

		GL30.glGetFramebufferAttachmentParameteriv(
				GL30.GL_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, SCRATCH);

		int texture = SCRATCH.get(0);

		// Guard against a driver that leaves the slot untouched on a renderbuffer
		// attachment, which would otherwise hand back whatever was there before.
		return texture == 0 ? 0 : texture;
	}

	/**
	 * The currently bound draw framebuffer, for diagnostics.
	 *
	 * <p>Not used by the renderer itself - it only needs the attachments - but
	 * this is the first thing worth knowing when the chain quietly does nothing,
	 * which is exactly the failure mode it is easy to end up in.
	 */
	public static int boundFramebuffer() {
		return GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
	}

	/** The colour texture of the bound framebuffer, or 0 for the default one. */
	public static int boundColorTexture() {
		return attachedTexture(GL30.GL_COLOR_ATTACHMENT0);
	}

	/** The depth texture of the bound framebuffer, or 0 when unavailable. */
	public static int boundDepthTexture() {
		return attachedTexture(GL30.GL_DEPTH_ATTACHMENT);
	}
}