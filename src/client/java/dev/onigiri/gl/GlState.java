package dev.onigiri.gl;

import java.nio.IntBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

/**
 * Saves and restores the GL state the pipeline touches.
 *
 * <p>Why this class exists: the pipeline issues raw GL calls in the middle of
 * the game's own rendering, and the game tracks its GL state in
 * {@code GlStateManager} - a cache that only knows about changes made through
 * itself. Every raw call made here bypasses that cache, so on exit the cache
 * says one thing and the driver holds another. The game's next draw then runs
 * with the wrong program, wrong VAO, wrong viewport or wrong samplers, and the
 * symptom is a broken or black screen <em>after</em> an otherwise working
 * post-process pass.
 *
 * <p>Two entries on this list deserve a call-out because each one silently
 * produces exactly the reported symptom:
 *
 * <ul>
 *   <li>Sampler objects. The game samples through its own {@code GpuSampler}
 *       objects, which stay bound to their units until changed. A sampler with
 *       a mipmapped min filter (or depth-compare mode) applied to a texture
 *       with no mip chain makes that texture sample as black - regardless of
 *       the texture's own parameters, which sampler objects override. Leaving
 *       the game's samplers bound while this mod samples the game textures is
 *       the most likely reason the composite read back as (0,1,2,255): a chain
 *       that executes correctly over black inputs. Every unit this pipeline
 *       uses is therefore detached from its sampler on entry.
 *   <li>The current program. Leaving the composite program bound means the
 *       game's next draw executes the wrong shader entirely.
 * </ul>
 *
 * <p>Only {@code TEXTURE_2D} bindings are tracked: this pipeline never binds
 * any other target, so nothing else can be disturbed by it. All scratch storage
 * is static; everything here runs on the render thread only.
 */
public final class GlState {
	private static final int UNITS = 8;

	private static final IntBuffer SCRATCH_INT = MemoryUtil.memAllocInt(4);

	private int program;
	private int vao;
	private int drawFbo;
	private int readFbo;
	private int activeUnit;
	private final int[] viewport = new int[4];
	private boolean depthTest;
	private boolean blend;
	private boolean cullFace;
	private boolean scissorTest;
	private boolean depthMask;

	private final int[] textureBindings = new int[UNITS];
	private final int[] samplerBindings = new int[UNITS];

	private GlState() {
	}

	public static GlState save() {
		GlState state = new GlState();

		state.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		state.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		state.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		state.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		state.activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

		SCRATCH_INT.clear();
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, SCRATCH_INT);
		SCRATCH_INT.get(state.viewport);

		state.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		state.blend = GL11.glIsEnabled(GL11.GL_BLEND);
		state.cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		state.scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		state.depthMask = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) != 0;

		for (int unit = 0; unit < UNITS; unit++) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			state.textureBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			state.samplerBindings[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);

			// Detach the game's sampler so this mod's raw sampling is governed
			// by the texture's own parameters (repaired in GameTarget) rather
			// than by whatever filtering the game last used the unit for.
			if (state.samplerBindings[unit] != 0) {
				GL33.glBindSampler(unit, 0);
			}
		}

		GL13.glActiveTexture(state.activeUnit);
		return state;
	}

	public void restore() {
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);

		GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

		GL20.glUseProgram(program);
		GL30.glBindVertexArray(vao);

		setEnabled(GL11.GL_DEPTH_TEST, depthTest);
		setEnabled(GL11.GL_BLEND, blend);
		setEnabled(GL11.GL_CULL_FACE, cullFace);
		setEnabled(GL11.GL_SCISSOR_TEST, scissorTest);
		GL11.glDepthMask(depthMask);

		for (int unit = 0; unit < UNITS; unit++) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindings[unit]);
			GL33.glBindSampler(unit, samplerBindings[unit]);
		}

		GL13.glActiveTexture(activeUnit);
	}

	private static void setEnabled(int capability, boolean enabled) {
		if (enabled) {
			GL11.glEnable(capability);
		} else {
			GL11.glDisable(capability);
		}
	}
}