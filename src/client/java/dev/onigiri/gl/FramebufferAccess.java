package dev.onigiri.gl;

import java.nio.IntBuffer;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Access to the game's main framebuffer attachments.
 *
 * <p>The renderer needs the colour and depth textures Minecraft already rendered
 * into. On 26.x those live behind {@code MainTarget} and {@code GpuTextureView},
 * which are abstractions over the active graphics backend - so rather than bind
 * against a class that may be a GL or Vulkan implementation, we read the
 * attachment IDs straight out of OpenGL.
 *
 * <p>That is the right trade here for two reasons: the shader pipeline is
 * inherently GL-specific (raw {@code GL30}/{@code GL31} calls, {@code RGBA16F}
 * targets, fullscreen triangle), and querying the bound framebuffer is both
 * cheaper and more stable than reflecting over the backend abstraction.
 */
public final class FramebufferAccess {
	private FramebufferAccess() {
	}

	/**
	 * Returns the framebuffer currently bound for drawing, which during a level
	 * render is the main render target.
	 */
	public static int currentFramebuffer() {
		return GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
	}

	/**
	 * Returns the texture object attached to {@code attachment} of the currently
	 * bound framebuffer, or {@code 0} when the slot is empty.
	 *
	 * @param attachment one of {@code GL30.GL_COLOR_ATTACHMENT0} or
	 *                   {@code GL30.GL_DEPTH_ATTACHMENT}
	 */
	public static int attachedTexture(int attachment) {
		int framebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

		if (framebuffer == 0) {
			// The default framebuffer has no addressable texture object.
			return 0;
		}

		int[] result = new int[1];
		GL30.glGetFramebufferAttachmentParameteriv(
				GL30.GL_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, result);

		if (GL30.glGetError() != GL30.GL_NO_ERROR) {
			return 0;
		}

		return result[0];
	}

	/** The colour texture of the bound framebuffer, or 0 for the default one. */
	public static int boundColorTexture() {
		return attachedTexture(GL30.GL_COLOR_ATTACHMENT0);
	}

	/** The depth texture of the bound framebuffer, or 0 when unavailable. */
	public static int boundDepthTexture() {
		return attachedTexture(GL30.GL_DEPTH_ATTACHMENT);
	}

	/**
	 * Reads a scalar render-buffer state value (such as
	 * {@code GL30.GL_SAMPLES}) from the currently bound framebuffer.
	 */
	public static int framebufferParameter(int pname) {
		int framebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

		IntBuffer result = MemoryUtil.memAllocInt(1);

		try {
			GL30.glGetFramebufferParameteriv(framebuffer, pname, result);
			return result.get(0);
		} finally {
			MemoryUtil.memFree(result);
		}
	}

}
