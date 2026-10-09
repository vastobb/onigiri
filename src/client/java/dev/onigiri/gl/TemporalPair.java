package dev.onigiri.gl;

/**
 * A pair of interchangeable render targets used for temporal accumulation.
 *
 * <p>The resolve pass reads history from one and writes to the other, then the
 * pair swaps. Reading and writing the same texture in one draw is undefined
 * behaviour in GL, so a genuine double buffer is required rather than optional.
 */
public final class TemporalPair implements AutoCloseable {
	private RenderTarget read;
	private RenderTarget write;

	public TemporalPair(int width, int height) {
		this.read = new RenderTarget(width, height);
		this.write = new RenderTarget(width, height);
	}

	/** The target holding last frame's resolved result. */
	public RenderTarget read() {
		return read;
	}

	/** The target to render this frame's resolved result into. */
	public RenderTarget write() {
		return write;
	}

	public void swap() {
		RenderTarget previous = read;
		read = write;
		write = previous;
	}

	public int width() {
		return read.width();
	}

	public int height() {
		return read.height();
	}

	public boolean resize(int width, int height) {
		boolean a = read.resize(width, height);
		boolean b = write.resize(width, height);
		return a || b;
	}

	@Override
	public void close() {
		read.close();
		write.close();
	}
}
