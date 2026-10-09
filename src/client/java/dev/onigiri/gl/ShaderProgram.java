package dev.onigiri.gl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A compiled and linked GLSL program, plus a memoised uniform-location cache.
 *
 * <p>Deliberately built on raw LWJGL rather than {@code RenderSystem}. The
 * pipeline runs inside a render pass and needs precise control over which
 * texture unit is bound to what; going through the game abstraction would add an
 * indirection we would immediately have to work around anyway. It also means the
 * only hard dependency on Minecraft internals is the framebuffer we read from.
 *
 * <p>Uniform locations are resolved on first use and cached. Asking the driver
 * for a location every frame is a string lookup across the API boundary, which
 * is exactly the sort of small cost that adds up across six passes at 60 Hz.
 */
public final class ShaderProgram implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("onigiri/shader");

	private static final String VERTEX_HEADER = "#version 150 core\n";
	private static final String VERTEX_BODY = """
			out vec2 vUv;
			void main() {
			    // Fullscreen triangle derived from gl_VertexID (GLSL 150).
			    // vertex 0 -> (0,0), 1 -> (2,0), 2 -> (0,2).
			    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
			    vUv = corner;
			    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	private final String name;
	private final int programId;
	private final Map<String, Integer> locations = new HashMap<>();
	private boolean disposed;

	private ShaderProgram(String name, int programId) {
		this.name = name;
		this.programId = programId;
	}

	/**
	 * Compiles a fragment shader from the mod's assets and links it against the
	 * shared fullscreen-triangle vertex shader.
	 *
	 * <p>{@code #include "file"} directives are resolved relative to the
	 * including file, which is how every pass pulls in {@code common.glsl}.
	 */
	public static ShaderProgram load(String assetPath) {
		String fragmentSource = resolveIncludes(assetPath, 0);

		int vertex = compile(nameOf(assetPath) + " [vertex]", VERTEX_HEADER + VERTEX_BODY, GL20.GL_VERTEX_SHADER);
		int fragment = compile(nameOf(assetPath), fragmentSource, GL20.GL_FRAGMENT_SHADER);

		int program = GL20.glCreateProgram();
		GL20.glAttachShader(program, vertex);
		GL20.glAttachShader(program, fragment);
		GL20.glLinkProgram(program);

		// The program holds its own reference to the shader objects once linked.
		GL20.glDetachShader(program, vertex);
		GL20.glDetachShader(program, fragment);
		GL20.glDeleteShader(vertex);
		GL20.glDeleteShader(fragment);

		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(program);
			GL20.glDeleteProgram(program);
			throw new IllegalStateException("Failed to link shader '" + assetPath + "':\n" + log);
		}

		LOGGER.debug("Linked shader {}", assetPath);
		return new ShaderProgram(nameOf(assetPath), program);
	}

	private static String nameOf(String assetPath) {
		int slash = assetPath.lastIndexOf('/');
		return slash >= 0 ? assetPath.substring(slash + 1) : assetPath;
	}

	/** Reads a shader asset, inlining nested {@code #include} directives. */
	private static String resolveIncludes(String assetPath, int depth) {
		if (depth > 8) {
			throw new IllegalStateException("Too many nested includes while reading " + assetPath);
		}

		String raw = readAsset(assetPath);
		List<String> out = new ArrayList<>(raw.length());

		for (String line : raw.split("\n", -1)) {
			String trimmed = line.strip();

			if (trimmed.startsWith("#include")) {
				int open = trimmed.indexOf('"');
				int close = trimmed.lastIndexOf('"');

				if (open >= 0 && close > open) {
					String included = trimmed.substring(open + 1, close);
					int slash = assetPath.lastIndexOf('/');
					String parent = slash >= 0 ? assetPath.substring(0, slash + 1) : "";

					out.add(resolveIncludes(parent + included, depth + 1));
					continue;
				}
			}

			out.add(line);
		}

		return String.join("\n", out);
	}

	private static String readAsset(String assetPath) {
		InputStream in = ShaderProgram.class.getResourceAsStream("/assets/" + assetPath);

		if (in == null) {
			throw new IllegalStateException("Missing shader asset: /assets/" + assetPath);
		}

		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read shader asset " + assetPath, e);
		}
	}

	private static int compile(String label, String source, int type) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);

		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetShaderInfoLog(shader);
			GL20.glDeleteShader(shader);
			throw new IllegalStateException("Failed to compile " + label + ":\n" + log + "\n" + withLineNumbers(source));
		}

		return shader;
	}

	/** The driver reports line numbers only for the log; this adds them to the source. */
	private static String withLineNumbers(String source) {
		String[] lines = source.split("\n", -1);
		StringBuilder sb = new StringBuilder(source.length() + lines.length * 6);

		for (int i = 0; i < lines.length; i++) {
			sb.append(i + 1).append(" | ").append(lines[i]).append('\n');
		}

		return sb.toString();
	}

	/**
	 * Resolves and caches a uniform location.
	 *
	 * <p>Returns -1 for uniforms the compiler optimised away, which is expected:
	 * a pass that never references a shared uniform from {@code common.glsl} will
	 * have it stripped. The setters skip -1 rather than erroring.
	 */
	private int loc(String uniform) {
		Integer cached = locations.get(uniform);

		if (cached == null) {
			cached = GL20.glGetUniformLocation(programId, uniform);
			locations.put(uniform, cached);
		}

		return cached;
	}

	public ShaderProgram bind() {
		GL20.glUseProgram(programId);
		return this;
	}

	public ShaderProgram uniform1i(String uniform, int value) {
		int location = loc(uniform);
		if (location >= 0) GL20.glUniform1i(location, value);
		return this;
	}

	public ShaderProgram uniform1f(String uniform, float value) {
		int location = loc(uniform);
		if (location >= 0) GL20.glUniform1f(location, value);
		return this;
	}

	public ShaderProgram uniform2f(String uniform, float x, float y) {
		int location = loc(uniform);
		if (location >= 0) GL20.glUniform2f(location, x, y);
		return this;
	}

	public ShaderProgram uniform3f(String uniform, float x, float y, float z) {
		int location = loc(uniform);
		if (location >= 0) GL20.glUniform3f(location, x, y, z);
		return this;
	}

	public ShaderProgram uniformMatrix(String uniform, Matrix4f value) {
		int location = loc(uniform);
		if (location >= 0) GL20.glUniformMatrix4fv(location, false, value);
		return this;
	}

	/** Binds {@code textureId} to {@code unit} and points the sampler at it. */
	public ShaderProgram texture(String uniform, int unit, int textureId) {
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL13.glBindTexture(GL13.GL_TEXTURE_2D, textureId);
		return uniform1i(uniform, unit);
	}

	public String name() {
		return name;
	}

	@Override
	public void close() {
		if (!disposed) {
			disposed = true;
			GL20.glDeleteProgram(programId);
		}
	}
}
