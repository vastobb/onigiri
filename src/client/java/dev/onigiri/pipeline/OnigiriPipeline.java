package dev.onigiri.pipeline;

import dev.onigiri.OnigiriConfig;
import dev.onigiri.gl.GlCaps;
import dev.onigiri.gl.RenderTarget;
import dev.onigiri.gl.ShaderProgram;
import dev.onigiri.gl.TemporalPair;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Onigiri post-processing chain.
 *
 * <p>Six passes per frame:
 *
 * <ol>
 *   <li>geometry  - rebuild view normals and depth from the depth buffer (full res)
 *   <li>ao        - horizon-based occlusion (half res)
 *   <li>ssr       - screen-space reflections (half res)
 *   <li>shadow    - screen-space sun shadows, quarter-rate (half res)
 *   <li>temporal  - reproject and resolve history (half res, ping-pong, x3)
 *   <li>composite - relight, fog, tonemap to the default framebuffer
 * </ol>
 *
 * <p>Only the composite and the geometry reconstruction run at full resolution.
 * Everything else is half res and temporally amortised, which is what keeps the
 * chain affordable on a tile-based mobile GPU.
 *
 * <p>State discipline: each pass binds the framebuffer it needs, and the chain
 * leaves the default framebuffer bound and depth writes re-enabled so the game
 * can carry straight on to the HUD.
 *
 * <p><strong>This class does not touch the matrices.</strong> They are built
 * exactly once per frame by {@link ProjectionModel}, including their inverses.
 * An earlier version inverted them again here, without ever assigning the
 * forward matrices first, which silently turned every depth reconstruction in
 * the shaders into a no-op. Duplicating that responsibility is the bug, not the
 * individual inversion.
 */
public final class OnigiriPipeline implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("onigiri/pipeline");

	private static final String SHADER_DIR = "onigiri/shaders/";

	/** Texture units, fixed up front so passes never contend for one. */
	private static final int UNIT_DEPTH = 0;
	private static final int UNIT_COLOR = 1;
	private static final int UNIT_NORMAL_DEPTH = 2;
	private static final int UNIT_CURRENT = 3;
	private static final int UNIT_HISTORY = 4;
	private static final int UNIT_AO = 5;
	private static final int UNIT_SSR = 6;
	private static final int UNIT_SHADOW = 7;
	private static final int UNIT_COUNT = 8;

	private ShaderProgram geometryShader;
	private ShaderProgram aoShader;
	private ShaderProgram ssrShader;
	private ShaderProgram shadowShader;
	private ShaderProgram temporalShader;
	private ShaderProgram compositeShader;

	private RenderTarget normalDepth;
	private RenderTarget aoTarget;
	private RenderTarget ssrTarget;
	private RenderTarget shadowTarget;

	/**
	 * Full-resolution target the composite pass draws into.
	 *
	 * <p>Drawing the relit image straight into the game's colour texture is not
	 * an option: that texture is also bound as the {@code uColor} sampler for
	 * the same draw, and sampling a texture that is attached to the draw
	 * framebuffer is a feedback loop, which is undefined behaviour in GL. The
	 * composite therefore renders here, and the result is blitted across.
	 */
	private RenderTarget compositeTarget;

	/**
	 * Scratch framebuffer used to blit the composite result into the game's
	 * colour texture. The game's texture is attached to this on every frame
	 * rather than cached, because the game reallocates its targets on resize
	 * and resource reload.
	 */
	private int blitFbo;

	private TemporalPair aoHistory;
	private TemporalPair ssrHistory;
	private TemporalPair shadowHistory;

	private final OnigiriConfig config;
	private final FrameState frame = new FrameState();

	private int width;
	private int height;
	private int effectWidth;
	private int effectHeight;

	private int emptyVao;
	private boolean attachmentsLogged;
	private boolean initialised;
	private boolean disposed;

	public OnigiriPipeline(OnigiriConfig config) {
		this.config = config;
	}

	/**
	 * Compiles shaders and allocates render targets.
	 *
	 * <p>Deferred to first use rather than mod init, because compiling needs a
	 * current GL context and none exists during {@code onInitializeClient}.
	 */
	public void initialise(int frameWidth, int frameHeight) {
		if (disposed) {
			throw new IllegalStateException("Pipeline already closed");
		}

		// Probe before allocating anything: the answer decides the format every
		// target is created with.
		GlCaps.probe();

		geometryShader = ShaderProgram.load(SHADER_DIR + "geometry.frag");
		aoShader = ShaderProgram.load(SHADER_DIR + "ao.frag");
		ssrShader = ShaderProgram.load(SHADER_DIR + "ssr.frag");
		shadowShader = ShaderProgram.load(SHADER_DIR + "shadow.frag");
		temporalShader = ShaderProgram.load(SHADER_DIR + "temporal.frag");
		compositeShader = ShaderProgram.load(SHADER_DIR + "composite.frag");

		width = Math.max(1, frameWidth);
		height = Math.max(1, frameHeight);

		// Core profile needs a bound vertex array even though the vertex shader
		// derives positions from gl_VertexID. An empty VAO satisfies that. GLES 3.0
		// has vertex array objects in core too, so this is correct on both paths.
		emptyVao = GL30.glGenVertexArrays();
		GL30.glBindVertexArray(emptyVao);

		blitFbo = GL30.glGenFramebuffers();

		computeEffectSize();
		allocateTargets();

		initialised = true;
		LOGGER.info("Pipeline ready ({}x{}, effects {}x{})", width, height, effectWidth, effectHeight);
	}

	private void computeEffectSize() {
		if (config.halfResolution) {
			float scale = Math.max(0.5f, Math.min(1.0f, config.resolutionScale));
			effectWidth = Math.max(1, Math.round(width * 0.5f * scale));
			effectHeight = Math.max(1, Math.round(height * 0.5f * scale));
		} else {
			effectWidth = width;
			effectHeight = height;
		}
	}

	private void allocateTargets() {
		normalDepth = new RenderTarget(width, height);
		compositeTarget = new RenderTarget(width, height);

		aoTarget = new RenderTarget(effectWidth, effectHeight);
		ssrTarget = new RenderTarget(effectWidth, effectHeight);
		shadowTarget = new RenderTarget(effectWidth, effectHeight);

		aoHistory = new TemporalPair(effectWidth, effectHeight);
		ssrHistory = new TemporalPair(effectWidth, effectHeight);
		shadowHistory = new TemporalPair(effectWidth, effectHeight);

		frame.requestHistoryReset();
	}

	/** Reallocates on resize or when the resolution settings change. */
	public void resize(int newWidth, int newHeight) {
		if (!initialised) {
			return;
		}

		int w = Math.max(1, newWidth);
		int h = Math.max(1, newHeight);

		if (w == width && h == height) {
			return;
		}

		width = w;
		height = h;
		computeEffectSize();

		normalDepth.resize(width, height);
		compositeTarget.resize(width, height);
		aoTarget.resize(effectWidth, effectHeight);
		ssrTarget.resize(effectWidth, effectHeight);
		shadowTarget.resize(effectWidth, effectHeight);
		aoHistory.resize(effectWidth, effectHeight);
		ssrHistory.resize(effectWidth, effectHeight);
		shadowHistory.resize(effectWidth, effectHeight);

		frame.requestHistoryReset();
	}

	/** Reacts to the resolution settings changing mid-session, e.g. from the menu. */
	public void effectScaleChanged() {
		if (!initialised) {
			return;
		}

		computeEffectSize();

		if (effectWidth == aoTarget.width() && effectHeight == aoTarget.height()) {
			return;
		}

		aoTarget.resize(effectWidth, effectHeight);
		ssrTarget.resize(effectWidth, effectHeight);
		shadowTarget.resize(effectWidth, effectHeight);
		aoHistory.resize(effectWidth, effectHeight);
		ssrHistory.resize(effectWidth, effectHeight);
		shadowHistory.resize(effectWidth, effectHeight);

		frame.requestHistoryReset();
	}

	public FrameState frame() {
		return frame;
	}

	/** True once shaders are compiled and targets exist. */
	public boolean isInitialised() {
		return initialised;
	}

	/**
	 * Logs, once, which game textures the chain is reading and writing.
	 *
	 * <p>This exists because the failure mode here is silent by design: when the
	 * game textures cannot be resolved the frame is skipped, the game looks like
	 * plain vanilla, and the log says nothing at all. One log line turns that
	 * from a mystery into a diagnosis.
	 */
	private void logGameTargetOnce(int gameColor, int gameDepth) {
		if (attachmentsLogged) {
			return;
		}

		attachmentsLogged = true;

		LOGGER.info("First frame: game colour texture {}, game depth texture {}",
				gameColor, gameDepth);

		if (gameColor == 0 || gameDepth == 0) {
			LOGGER.warn("The game's render target is not readable - post-processing is being skipped. "
					+ "colour={} depth={}.", gameColor, gameDepth);
		}
	}

	/**
	 * Runs one frame.
	 *
	 * @param gameColor the GL id of the game's colour texture, from its main
	 *                  render target - the image the world has been drawn into
	 * @param gameDepth the GL id of the game's depth texture
	 */
	public void render(int gameColor, int gameDepth) {
		if (!initialised) {
			return;
		}

		logGameTargetOnce(gameColor, gameDepth);

		// Without both game textures there is nothing to read from. Drop history
		// and let the next frame try again.
		if (gameColor == 0 || gameDepth == 0) {
			frame.requestHistoryReset();
			return;
		}

		// Effects sample and write colour; blending and depth are done in-shader.
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL30.glBindVertexArray(emptyVao);

		// Matrices are already built and inverted by ProjectionModel. Nothing here
		// touches them.
		runGeometryPass(gameDepth);
		runAoPass(gameDepth);
		runSsrPass(gameDepth, gameColor);
		runShadowPass(gameDepth);

		resolveTemporal(aoTarget, aoHistory, gameDepth);
		resolveTemporal(ssrTarget, ssrHistory, gameDepth);
		resolveTemporal(shadowTarget, shadowHistory, gameDepth);

		runCompositePass(gameDepth, gameColor);
		blitToGame(gameColor);

		frame.resetHistory = false;
		frame.advanceFrame(frame.time);

		unbindTextures();

		// Leave the game the state it expects for whatever renders next. FBO 0 was
		// bound on entry (the world lives in GPU textures, not a bound
		// framebuffer, on this path), so that is what is restored.
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GL11.glDepthMask(true);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
	}

	/**
	 * Copies the relit image over the game's colour texture.
	 *
	 * <p>A blit rather than a draw: the composite target and the game texture are
	 * the same size, so this is a straight GPU-side copy with no shader, no
	 * sampler and no feedback loop. NEAREST because this is a copy, not a scale.
	 */
	private void blitToGame(int gameColor) {
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, compositeTarget.framebufferId());
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitFbo);
		GL30.glFramebufferTexture2D(
				GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL30.GL_TEXTURE_2D, gameColor, 0);

		GL30.glBlitFramebuffer(
				0, 0, compositeTarget.width(), compositeTarget.height(),
				0, 0, compositeTarget.width(), compositeTarget.height(),
				GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
	}

	/** Uniforms shared by every pass that includes {@code common.glsl}. */
	private void applyCommon(ShaderProgram shader, int depthTexture, int colorTexture) {
		if (depthTexture != 0) {
			shader.texture("uDepth", UNIT_DEPTH, depthTexture);
		}

		if (colorTexture != 0) {
			shader.texture("uColor", UNIT_COLOR, colorTexture);
		}

		shader.uniformMatrix("uProj", frame.projection);
		shader.uniformMatrix("uInvProj", frame.inverseProjection);
		shader.uniformMatrix("uView", frame.view);
		shader.uniformMatrix("uInvView", frame.viewInverse);
		shader.uniformMatrix("uViewProj", frame.viewProjection);
		shader.uniformMatrix("uInvViewProj", frame.inverseViewProjection);
		shader.uniformMatrix("uPrevViewProj", frame.previousViewProjection);

		shader.uniform2f("uResolution", width, height);
		shader.uniform2f("uEffectResolution", effectWidth, effectHeight);

		shader.uniform3f("uCameraPos", frame.cameraPos.x, frame.cameraPos.y, frame.cameraPos.z);
		shader.uniform3f("uSunDir", frame.sunDirection.x, frame.sunDirection.y, frame.sunDirection.z);
		shader.uniform3f("uSunDirView", frame.sunDirectionView.x, frame.sunDirectionView.y, frame.sunDirectionView.z);
		shader.uniform3f("uSunColor", frame.sunColor.x, frame.sunColor.y, frame.sunColor.z);
		shader.uniform3f("uSkyColor", frame.skyColor.x, frame.skyColor.y, frame.skyColor.z);
		shader.uniform3f("uAmbientColor", frame.ambientColor.x, frame.ambientColor.y, frame.ambientColor.z);

		shader.uniform1f("uNear", frame.near);
		shader.uniform1f("uFar", frame.far);
		shader.uniform1f("uFrame", frame.frameCounter % 4096);
		shader.uniform1f("uTime", frame.time);
	}

	/** Pass 1: view-space normals and linear depth, from the depth buffer alone. */
	private void runGeometryPass(int depthTexture) {
		normalDepth.bindForDrawing();

		geometryShader.bind();
		applyCommon(geometryShader, depthTexture, 0);

		drawFullscreen();
	}

	/** Pass 2: horizon-based ambient occlusion. */
	private void runAoPass(int depthTexture) {
		if (!config.ambientOcclusion) {
			// Fully open, so the composite leaves ambient alone.
			clear(aoTarget, 1.0f, 1.0f, 1.0f, 1.0f);
			return;
		}

		aoTarget.bindForDrawing();

		aoShader.bind();
		applyCommon(aoShader, depthTexture, 0);
		aoShader.texture("uNormalDepth", UNIT_NORMAL_DEPTH, normalDepth.colorTextureId());
		aoShader.uniform1i("uDirections", config.aoDirections());
		aoShader.uniform1i("uSteps", config.aoSteps());
		aoShader.uniform1f("uRadius", 0.75f);

		drawFullscreen();
	}

	/** Pass 3: screen-space reflections. */
	private void runSsrPass(int depthTexture, int colorTexture) {
		if (!config.reflections) {
			// No reflection contribution.
			clear(ssrTarget, 0.0f, 0.0f, 0.0f, 0.0f);
			return;
		}

		ssrTarget.bindForDrawing();

		ssrShader.bind();
		applyCommon(ssrShader, depthTexture, colorTexture);
		ssrShader.texture("uNormalDepth", UNIT_NORMAL_DEPTH, normalDepth.colorTextureId());
		ssrShader.uniform1i("uSteps", config.ssrSteps());
		ssrShader.uniform1f("uMaxDistance", 48.0f);
		ssrShader.uniform1f("uThickness", 0.55f);
		ssrShader.uniform1f("uMaxRoughness", 0.92f);

		drawFullscreen();
	}

	/**
	 * Pass 4: screen-space sun shadows.
	 *
	 * <p>A quarter of the pixels trace a ray this frame; the temporal pass fills
	 * in the rest. See {@code shadow.frag} for why that is affordable.
	 */
	private void runShadowPass(int depthTexture) {
		if (!config.shadows) {
			clear(shadowTarget, 1.0f, 1.0f, 1.0f, 1.0f);
			return;
		}

		shadowTarget.bindForDrawing();

		shadowShader.bind();
		applyCommon(shadowShader, depthTexture, 0);
		shadowShader.texture("uNormalDepth", UNIT_NORMAL_DEPTH, normalDepth.colorTextureId());
		shadowShader.uniform1i("uSteps", config.shadowSteps());
		shadowShader.uniform1f("uMaxDistance", 24.0f);
		shadowShader.uniform1f("uThickness", 0.35f);
		shadowShader.uniform1f("uSoftness", 0.8f);

		drawFullscreen();
	}

	/**
	 * Pass 5: temporal resolve for one effect.
	 *
	 * <p>Reads {@code source} plus {@code pair}'s read target, writes into its
	 * write target, then swaps so the resolved result becomes next frame's
	 * history. Reading and writing one texture in a single draw is undefined in
	 * GL, so the double buffer is required rather than an optimisation.
	 */
	private void resolveTemporal(RenderTarget source, TemporalPair pair, int depthTexture) {
		pair.write().bindForDrawing();

		temporalShader.bind();
		applyCommon(temporalShader, depthTexture, 0);
		temporalShader.texture("uCurrent", UNIT_CURRENT, source.colorTextureId());
		temporalShader.texture("uHistory", UNIT_HISTORY, pair.read().colorTextureId());
		temporalShader.texture("uNormalDepth", UNIT_NORMAL_DEPTH, normalDepth.colorTextureId());

		temporalShader.uniform2f("uSourceTexel", 1.0f / pair.width(), 1.0f / pair.height());
		temporalShader.uniform1f("uFeedback", config.temporalFeedback);
		temporalShader.uniform1f("uReset", frame.resetHistory ? 1.0f : 0.0f);
		temporalShader.uniform1i("uHasHistory", frame.frameCounter > 1 ? 1 : 0);

		drawFullscreen();

		pair.swap();
	}

	/**
	 * Pass 6: relight, fog and tonemap into the composite target.
	 *
	 * <p>Into our own target, not the game's: the game's colour texture is bound
	 * as the {@code uColor} sampler for this same draw, and sampling a texture
	 * attached to the draw framebuffer is a feedback loop. {@link #blitToGame}
	 * copies the result across afterwards.
	 */
	private void runCompositePass(int depthTexture, int colorTexture) {
		compositeTarget.bindForDrawing();

		compositeShader.bind();
		applyCommon(compositeShader, depthTexture, colorTexture);
		compositeShader.texture("uNormalDepth", UNIT_NORMAL_DEPTH, normalDepth.colorTextureId());
		compositeShader.texture("uAO", UNIT_AO, aoHistory.read().colorTextureId());
		compositeShader.texture("uSSR", UNIT_SSR, ssrHistory.read().colorTextureId());
		compositeShader.texture("uShadow", UNIT_SHADOW, shadowHistory.read().colorTextureId());

		compositeShader.uniform1f("uAOStrength", config.aoStrength);
		compositeShader.uniform1f("uSSRStrength", config.ssrStrength);
		compositeShader.uniform1f("uShadowStrength", config.shadowStrength);
		compositeShader.uniform1f("uSpecularStrength", config.specularStrength);
		compositeShader.uniform1f("uExposure", config.exposure);
		compositeShader.uniform1f("uVignette", config.vignette);
		compositeShader.uniform1f("uSaturation", config.saturation);
		compositeShader.uniform1f("uNightLift", config.nightLift);
		compositeShader.uniform1f("uFogNear", frame.far * 0.55f);
		compositeShader.uniform1f("uFogFar", frame.far * 0.95f);

		drawFullscreen();
	}

	private void drawFullscreen() {
		GL30.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	private void clear(RenderTarget target, float r, float g, float b, float a) {
		target.bindForDrawing();
		GL11.glClearColor(r, g, b, a);
		GL30.glClear(GL30.GL_COLOR_BUFFER_BIT);
	}

	/**
	 * Unbinds every texture unit the pipeline used.
	 *
	 * <p>Leaving a render target bound while the game draws the HUD would make
	 * the driver complain about feedback loops, and stale samplers cost cache
	 * misses on the next frame.
	 */
	private void unbindTextures() {
		for (int unit = 0; unit < UNIT_COUNT; unit++) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			GL13.glBindTexture(GL13.GL_TEXTURE_2D, 0);
		}

		// Return to unit 0. Some drivers - notably tiled mobile ones - treat the
		// active unit as part of their frame state, and the game's own drawing
		// code assumes it starts from the default.
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int effectWidth() {
		return effectWidth;
	}

	public int effectHeight() {
		return effectHeight;
	}

	@Override
	public void close() {
		if (disposed) {
			return;
		}

		disposed = true;

		closeQuietly(geometryShader);
		closeQuietly(aoShader);
		closeQuietly(ssrShader);
		closeQuietly(shadowShader);
		closeQuietly(temporalShader);
		closeQuietly(compositeShader);

		closeQuietly(normalDepth);
		closeQuietly(compositeTarget);
		closeQuietly(aoTarget);
		closeQuietly(ssrTarget);
		closeQuietly(shadowTarget);
		closeQuietly(aoHistory);
		closeQuietly(ssrHistory);
		closeQuietly(shadowHistory);

		if (emptyVao != 0) {
			GL30.glDeleteVertexArrays(emptyVao);
			emptyVao = 0;
		}

		if (blitFbo != 0) {
			GL30.glDeleteFramebuffers(blitFbo);
			blitFbo = 0;
		}

		initialised = false;
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}

		try {
			closeable.close();
		} catch (Exception e) {
			LOGGER.warn("Failed to close {}", closeable.getClass().getSimpleName(), e);
		}
	}
}