package dev.onigiri.gl;

import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time probe of what the current driver will actually let us do.
 *
 * <p>The renderer is built for Android, where Minecraft reaches GL through
 * MobileGlues: a translation layer that presents the GLES 3.x API and lowers it
 * onto Vulkan. Everything here therefore has to be treated as a <em>floor</em>,
 * not a hint. Two things follow from that and drive the whole design:
 *
 * <ul>
 *   <li>Shaders are written in GLSL ES 3.00 ({@code #version 300 es}), never a
 *       desktop profile. ES 3.00 is a strict subset of desktop GL 3.2, so the
 *       same source also runs on a desktop client, which keeps CI meaningful.
 *   <li>No call newer than GL 3.1 / GLES 3.1 is used anywhere. In particular
 *       {@code glTexStorage2D} is avoided in favour of {@code glTexImage2D}:
 *       the former is GL 4.2 and GLES 3.1, and the translation layer is not
 *       guaranteed to expose it.
 * </ul>
 *
 * <p>Mobile GPUs are bandwidth-bound, not ALU-bound, so the one thing worth
 * probing at runtime is the render target format. Half-float targets survive
 * highlights before the tonemap but cost twice the bytes of RGBA8, which on a
 * phone at 1080p is the difference between fitting in the bandwidth budget and
 * missing frame. Low-end Mali/Adreno parts frequently lack
 * {@code EXT_color_buffer_half_float}, so {@link #hdrFormat()} can legitimately
 * answer "no".
 */
public final class GlCaps {
	private static final Logger LOGGER = LoggerFactory.getLogger("onigiri/gl");

	/** Internal formats we are willing to allocate a render target with. */
	public static final int FORMAT_RGBA8 = 0x8058;   // GL_RGBA8
	public static final int FORMAT_RGBA16F = 0x881A; // GL_RGBA16F

	private static boolean probed;
	private static boolean hdrRenderable;
	private static String renderer;
	private static String version;

	private GlCaps() {
	}

	/**
	 * Reads the driver strings and picks a colour format. Safe to call more than
	 * once; only the first call does any work.
	 */
	public static synchronized void probe() {
		if (probed) {
			return;
		}

		probed = true;

		try {
			version = GL30.glGetString(GL30.GL_VERSION);
			renderer = GL30.glGetString(GL30.GL_RENDERER);
		} catch (RuntimeException | LinkageError e) {
			// No usable context. Leave everything at the conservative default;
			// the pipeline will fail soft later if it truly cannot run.
			LOGGER.warn("GL strings unavailable ({})", e.toString());
		}

		hdrRenderable = hasExtension("GL_EXT_color_buffer_half_float")
				|| hasExtension("GL_EXT_color_buffer_float");

		// A driver that claims support but then refuses the framebuffer is common
		// enough on translated stacks that the check has to be empirical.
		if (hdrRenderable && !framebufferAccepts(FORMAT_RGBA16F)) {
			LOGGER.info("Driver advertises half-float targets but rejects them; using RGBA8");
			hdrRenderable = false;
		}

		LOGGER.info("GL: {} | {}", version, renderer);
		LOGGER.info("Half-float render targets: {}", hdrRenderable ? "yes" : "no (falling back to RGBA8)");
	}

	/** The colour format to allocate targets with. */
	public static int hdrFormat() {
		probe();
		return hdrRenderable ? FORMAT_RGBA16F : FORMAT_RGBA8;
	}

	/** True when the driver can render into a half-float attachment. */
	public static boolean supportsHdrTargets() {
		probe();
		return hdrRenderable;
	}

	public static String renderer() {
		probe();
		return renderer == null ? "unknown" : renderer;
	}

	public static String version() {
		probe();
		return version == null ? "unknown" : version;
	}

	private static boolean hasExtension(String name) {
		try {
			return org.lwjgl.opengl.GL30.glGetString(GL30.GL_EXTENSIONS) != null
					&& org.lwjgl.opengl.GL30.glGetString(GL30.GL_EXTENSIONS).contains(name);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * Allocates a 1x1 target in the given format and asks the driver whether the
	 * result is complete.
	 *
	 * <p>Cheap, and the only trustworthy test: extension strings on a translation
	 * layer describe the layer's own capabilities rather than the underlying
	 * device's, so they lie often enough to matter here.
	 */
	private static boolean framebufferAccepts(int internalFormat) {
		int framebuffer = 0;
		int texture = 0;

		try {
			framebuffer = GL30.glGenFramebuffers();
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);

			texture = GL30.glGenTextures();
			GL30.glBindTexture(GL30.GL_TEXTURE_2D, texture);

			GL30.glTexImage2D(
					GL30.GL_TEXTURE_2D, 0, internalFormat, 1, 1, 0,
					GL30.GL_RGBA,
					internalFormat == FORMAT_RGBA16F ? GL30.GL_HALF_FLOAT : GL30.GL_UNSIGNED_BYTE,
					org.lwjgl.system.MemoryUtil.NULL);

			GL30.glFramebufferTexture2D(
					GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
					GL30.GL_TEXTURE_2D, texture, 0);

			return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
		} catch (RuntimeException | LinkageError e) {
			return false;
		} finally {
			if (framebuffer != 0) {
				GL30.glDeleteFramebuffers(framebuffer);
			}

			if (texture != 0) {
				GL30.glDeleteTextures(texture);
			}

			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		}
	}
}