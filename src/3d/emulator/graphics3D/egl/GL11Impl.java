package emulator.graphics3D.egl;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL11Ext;
import javax.microedition.khronos.opengles.GL11ExtensionPack;
import java.nio.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GL11 with software fallback for OES_matrix_palette
 */
public final class GL11Impl extends GL10Impl implements javax.microedition.khronos.opengles.GL11, GL11Ext, GL11ExtensionPack {
	private static final int OES_MATRIX_PALETTE = 34880; // GL_MATRIX_PALETTE_OES
	private static final int OES_MATRIX_INDEX_ARRAY = 34884;
	private static final int OES_WEIGHT_ARRAY = 34477;
	private static final int OES_CURRENT_PALETTE_MATRIX = 34883;

	private static final int GL_BYTE = 0x1400;
	private static final int GL_UNSIGNED_BYTE = 0x1401;
	private static final int GL_SHORT = 0x1402;
	private static final int GL_UNSIGNED_SHORT = 0x1403;
	private static final int GL_FLOAT = 0x1406;
	private static final int GL_FIXED = 0x140C; // 5132

	private boolean oesMatrixPaletteEnabled = false;
	private boolean oesMatrixIndexArrayEnabled = false;
	private boolean oesWeightArrayEnabled = false;

	private Buffer oesMatrixIndexBuffer = null;
	private int oesMatrixIndexSize;
	private int oesMatrixIndexType;
	private int oesMatrixIndexStride;
	private int oesMatrixIndexOffset;
	private boolean oesMatrixIndexIsOffset = false;

	private Buffer oesWeightBuffer;
	private int oesWeightSize;
	private int oesWeightType;
	private int oesWeightStride;
	private int oesWeightOffset;
	private boolean oesWeightIsOffset;

	private final float[][] paletteMatrices = new float[32][16];
	private int currentPaletteMatrix = 0;
	private int currentMatrixMode = 5888; // GL_MODELVIEW
	private final Deque<float[]> paletteStack = new ArrayDeque<>();

	protected int vertexSize;
	protected int vertexType;
	protected int vertexStride;
	protected int vertexOffset;
	protected boolean vertexIsOffset;
	protected Buffer vertexBuffer;

	protected boolean normalArrayEnabled;
	protected int normalType;
	protected int normalStride;
	protected Buffer normalBuffer;
	protected boolean normalIsOffset;
	protected int normalOffset;

	public final synchronized boolean glIsBuffer(final int n) {
		EGL10Impl.g3d.sync(() -> temp = GL15.glIsBuffer(n));
		return (boolean) temp;
	}

	public final synchronized boolean glIsEnabled(final int n) {
		EGL10Impl.g3d.sync(() -> temp = GL11.glIsEnabled(n));
		return (boolean) temp;
	}

	public final synchronized boolean glIsTexture(final int n) {
		EGL10Impl.g3d.sync(() -> temp = GL11.glIsTexture(n));
		return (boolean) temp;
	}

	public final synchronized void glGenBuffers(final int n, final int[] array, final int n2) {
		final IntBuffer intBuffer = BufferUtils.createIntBuffer(n);
		EGL10Impl.g3d.sync(() -> GL15.glGenBuffers(intBuffer));
		intBuffer.get(array, n2, n);
	}

	public final synchronized void glGenBuffers(final int n, final IntBuffer intBuffer) {
		EGL10Impl.g3d.sync(() -> GL15.glGenBuffers(intBuffer));
	}

	public final synchronized void glDeleteBuffers(final int n, final int[] array, final int n2) {
		final IntBuffer intBuffer;
		(intBuffer = BufferUtils.createIntBuffer(n)).put(array, n2, n);
		intBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL15.glDeleteBuffers(intBuffer));
	}

	public final synchronized void glDeleteBuffers(final int n, final IntBuffer intBuffer) {
		EGL10Impl.g3d.sync(() -> GL15.glDeleteBuffers(intBuffer));
	}

	public final synchronized void glBindBuffer(final int n, final int n2) {
		EGL10Impl.g3d.sync(() -> GL15.glBindBuffer(n, n2));
	}

	public final synchronized void glBufferData(final int n, final int n2, final Buffer buffer, final int n3) {
		if (buffer instanceof ByteBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferData(n, (ByteBuffer) buffer, n3));
			return;
		}
		if (buffer instanceof ShortBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferData(n, (ShortBuffer) buffer, n3));
			return;
		}
		if (buffer instanceof IntBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferData(n, (IntBuffer) buffer, n3));
			return;
		}
		if (buffer instanceof FloatBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferData(n, (FloatBuffer) buffer, n3));
		}
	}

	public final synchronized void glBufferSubData(final int n, final int n2, final int n3, final Buffer buffer) {
		if (buffer instanceof ByteBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferSubData(n, (long) n2, (ByteBuffer) buffer));
			return;
		}
		if (buffer instanceof ShortBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferSubData(n, (long) n2, (ShortBuffer) buffer));
			return;
		}
		if (buffer instanceof IntBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferSubData(n, (long) n2, (IntBuffer) buffer));
			return;
		}
		if (buffer instanceof FloatBuffer) {
			EGL10Impl.g3d.sync(() -> GL15.glBufferSubData(n, (long) n2, (FloatBuffer) buffer));
		}
	}

	public final synchronized void glGetBufferParameteriv(final int n, final int n2, final int[] array, final int n3) {
		final IntBuffer intBuffer;
		(intBuffer = BufferUtils.createIntBuffer(n2)).put(array, n3, GLConfiguration.method769());
		intBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL15.glGetBufferParameteriv(n, n2, intBuffer));
	}

	public final synchronized void glGetBufferParameteriv(final int n, final int n2, final IntBuffer intBuffer) {
		EGL10Impl.g3d.sync(() -> GL15.glGetBufferParameteriv(n, n2, intBuffer));
	}

	public final synchronized void glColorPointer(final int n, final int n2, final int n3, final int n4) {
		EGL10Impl.g3d.sync(() -> GL11.glColorPointer(n, n2, n3, (long) n4));
	}

	public final synchronized void glNormalPointer(final int type, final int stride, final Buffer pointer) {
		this.normalType = type;
		this.normalStride = stride;
		this.normalBuffer = pointer;
		this.normalIsOffset = false;
		EGL10Impl.g3d.sync(() -> GL11.glNormalPointer(type, stride, MemoryUtil.memAddress(pointer)));
	}

	public final synchronized void glNormalPointer(final int type, final int stride, final int offset) {
		this.normalType = type;
		this.normalStride = stride;
		this.normalOffset = offset;
		this.normalIsOffset = true;
		this.normalBuffer = null;
		EGL10Impl.g3d.sync(() -> GL11.glNormalPointer(type, stride, offset));
	}

	public final synchronized void glTexCoordPointer(final int n, final int n2, final int n3, final int n4) {
		EGL10Impl.g3d.sync(() -> GL11.glTexCoordPointer(n, n2, n3, (long) n4));
	}

	// ---- Vertex pointer with generic client-side buffer support ----
	public synchronized void glVertexPointer(final int size, final int type, final int stride, final Buffer pointer) {
		this.vertexSize = size;
		this.vertexType = type;
		this.vertexStride = stride;
		this.vertexIsOffset = false;
		this.vertexBuffer = pointer;
		EGL10Impl.g3d.sync(() -> GL11.glVertexPointer(size, type, stride, MemoryUtil.memAddress(pointer)));
	}

	public synchronized void glVertexPointer(final int size, final int type, final int stride, final int pointerOffset) {
		this.vertexSize = size;
		this.vertexType = type;
		this.vertexStride = stride;
		this.vertexOffset = pointerOffset;
		this.vertexIsOffset = true;
		this.vertexBuffer = null;
		EGL10Impl.g3d.sync(() -> GL11.glVertexPointer(size, type, stride, pointerOffset));
	}

	// ---- Matrix mode handling for palette emulation ----
	@Override
	public final synchronized void glMatrixMode(final int mode) {
		if (mode == OES_MATRIX_PALETTE) {
			this.currentMatrixMode = mode;
			GL10Impl.anInt1354 = mode;
			return;
		}
		this.currentMatrixMode = mode;
		GL10Impl.anInt1354 = mode;
		EGL10Impl.g3d.async(() -> GL11.glMatrixMode(mode));
	}

	@Override
	public final synchronized void glPushMatrix() {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			paletteStack.push(paletteMatrices[currentPaletteMatrix].clone());
			return;
		}
		EGL10Impl.g3d.async(GL11::glPushMatrix);
	}

	@Override
	public final synchronized void glPopMatrix() {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			if (!paletteStack.isEmpty()) {
				float[] popped = paletteStack.pop();
				System.arraycopy(popped, 0, paletteMatrices[currentPaletteMatrix], 0, 16);
			}
			return;
		}
		EGL10Impl.g3d.async(GL11::glPopMatrix);
	}

	@Override
	public final synchronized void glLoadIdentity() {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			identity(paletteMatrices[currentPaletteMatrix]);
			return;
		}
		EGL10Impl.g3d.async(GL11::glLoadIdentity);
	}

	@Override
	public final synchronized void glLoadMatrixf(final float[] array, final int offset) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			System.arraycopy(array, offset, paletteMatrices[currentPaletteMatrix], 0, 16);
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		fb.put(array, offset, 16);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glLoadMatrixf(fb));
	}

	@Override
	public final synchronized void glLoadMatrixf(final FloatBuffer floatBuffer) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			for (int i = 0; i < 16; i++) {
				paletteMatrices[currentPaletteMatrix][i] = floatBuffer.get(floatBuffer.position() + i);
			}
			return;
		}
		EGL10Impl.g3d.sync(() -> GL11.glLoadMatrixf(floatBuffer));
	}

	@Override
	public final synchronized void glLoadMatrixx(final int[] array, final int offset) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] m = paletteMatrices[currentPaletteMatrix];
			for (int i = 0; i < 16; i++) m[i] = array[offset + i] / 65536.0f;
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		for (int i = 0; i < 16; i++) fb.put(array[offset + i] / 65536.0f);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glLoadMatrixf(fb));
	}

	@Override
	public final synchronized void glLoadMatrixx(final IntBuffer intBuffer) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] m = paletteMatrices[currentPaletteMatrix];
			int pos = intBuffer.position();
			for (int i = 0; i < 16; i++) m[i] = intBuffer.get(pos + i) / 65536.0f;
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		int pos = intBuffer.position();
		for (int i = 0; i < 16; i++) fb.put(intBuffer.get(pos + i) / 65536.0f);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glLoadMatrixf(fb));
	}

	@Override
	public final synchronized void glMultMatrixf(final float[] array, final int offset) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] src = new float[16];
			System.arraycopy(array, offset, src, 0, 16);
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, src, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		fb.put(array, offset, 16);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glMultMatrixf(fb));
	}

	@Override
	public final synchronized void glMultMatrixf(final FloatBuffer floatBuffer) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] src = new float[16];
			for (int i = 0; i < 16; i++) src[i] = floatBuffer.get(floatBuffer.position() + i);
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, src, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		EGL10Impl.g3d.sync(() -> GL11.glMultMatrixf(floatBuffer));
	}

	@Override
	public final synchronized void glMultMatrixx(final int[] array, final int offset) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] src = new float[16];
			for (int i = 0; i < 16; i++) src[i] = array[offset + i] / 65536.0f;
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, src, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		for (int i = 0; i < 16; i++) fb.put(array[offset + i] / 65536.0f);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glMultMatrixf(fb));
	}

	@Override
	public final synchronized void glMultMatrixx(final IntBuffer intBuffer) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] src = new float[16];
			int pos = intBuffer.position();
			for (int i = 0; i < 16; i++) src[i] = intBuffer.get(pos + i) / 65536.0f;
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, src, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		int pos = intBuffer.position();
		for (int i = 0; i < 16; i++) fb.put(intBuffer.get(pos + i) / 65536.0f);
		fb.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glMultMatrixf(fb));
	}

	@Override
	public final synchronized void glTranslatef(final float x, final float y, final float z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			translateMat(paletteMatrices[currentPaletteMatrix], x, y, z);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glTranslatef(x, y, z));
	}

	@Override
	public final synchronized void glTranslatex(final int x, final int y, final int z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			translateMat(paletteMatrices[currentPaletteMatrix], x / 65536.0f, y / 65536.0f, z / 65536.0f);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glTranslatef(x / 65536.0f, y / 65536.0f, z / 65536.0f));
	}

	@Override
	public final synchronized void glScalef(final float x, final float y, final float z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			scaleMat(paletteMatrices[currentPaletteMatrix], x, y, z);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glScalef(x, y, z));
	}

	@Override
	public final synchronized void glScalex(final int x, final int y, final int z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			scaleMat(paletteMatrices[currentPaletteMatrix], x / 65536.0f, y / 65536.0f, z / 65536.0f);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glScalef(x / 65536.0f, y / 65536.0f, z / 65536.0f));
	}

	@Override
	public final synchronized void glRotatef(final float angle, final float x, final float y, final float z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			rotateMat(paletteMatrices[currentPaletteMatrix], angle, x, y, z);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glRotatef(angle, x, y, z));
	}

	@Override
	public final synchronized void glRotatex(final int angle, final int x, final int y, final int z) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			rotateMat(paletteMatrices[currentPaletteMatrix], angle / 65536.0f, x / 65536.0f, y / 65536.0f, z / 65536.0f);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glRotatef(angle / 65536.0f, x / 65536.0f, y / 65536.0f, z / 65536.0f));
	}

	@Override
	public synchronized void glFrustumf(float left, float right, float bottom, float top, float zNear, float zFar) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] f = new float[16];
			makeFrustum(f, left, right, bottom, top, zNear, zFar);
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, f, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glFrustum(left, right, bottom, top, (double) zNear, (double) zFar));
	}

	@Override
	public synchronized void glFrustumx(int left, int right, int bottom, int top, int zNear, int zFar) {
		glFrustumf(left / 65536.0f, right / 65536.0f, bottom / 65536.0f, top / 65536.0f, zNear / 65536.0f, zFar / 65536.0f);
	}

	@Override
	public synchronized void glOrthof(float left, float right, float bottom, float top, float zNear, float zFar) {
		if (currentMatrixMode == OES_MATRIX_PALETTE) {
			float[] o = new float[16];
			makeOrtho(o, left, right, bottom, top, zNear, zFar);
			float[] dst = paletteMatrices[currentPaletteMatrix];
			float[] res = new float[16];
			mulMatMat(dst, o, res);
			System.arraycopy(res, 0, dst, 0, 16);
			return;
		}
		EGL10Impl.g3d.async(() -> GL11.glOrtho(left, right, bottom, top, zNear, zFar));
	}

	@Override
	public synchronized void glOrthox(int left, int right, int bottom, int top, int zNear, int zFar) {
		glOrthof(left / 65536.0f, right / 65536.0f, bottom / 65536.0f, top / 65536.0f, zNear / 65536.0f, zFar / 65536.0f);
	}

	public final synchronized void glClipPlanef(final int n, final float[] array, final int n2) {
		final DoubleBuffer doubleBuffer;
		(doubleBuffer = BufferUtils.createDoubleBuffer(4)).put(array[n2]);
		doubleBuffer.put(array[n2 + 1]);
		doubleBuffer.put(array[n2 + 2]);
		doubleBuffer.put(array[n2 + 3]);
		doubleBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glClipPlane(n, doubleBuffer));
	}

	public final synchronized void glClipPlanef(final int n, final FloatBuffer floatBuffer) {
		final DoubleBuffer doubleBuffer;
		(doubleBuffer = BufferUtils.createDoubleBuffer(4)).put(floatBuffer.get());
		doubleBuffer.put(floatBuffer.get());
		doubleBuffer.put(floatBuffer.get());
		doubleBuffer.put(floatBuffer.get());
		doubleBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glClipPlane(n, doubleBuffer));
	}

	public final synchronized void glClipPlanex(final int n, final int[] array, final int n2) {
		final DoubleBuffer doubleBuffer;
		(doubleBuffer = BufferUtils.createDoubleBuffer(4)).put(array[n2] / 65536.0f);
		doubleBuffer.put(array[n2 + 1] / 65536.0f);
		doubleBuffer.put(array[n2 + 2] / 65536.0f);
		doubleBuffer.put(array[n2 + 3] / 65536.0f);
		doubleBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glClipPlane(n, doubleBuffer));
	}

	public final synchronized void glClipPlanex(final int n, final IntBuffer intBuffer) {
		final DoubleBuffer doubleBuffer;
		(doubleBuffer = BufferUtils.createDoubleBuffer(4)).put(intBuffer.get() / 65536.0f);
		doubleBuffer.put(intBuffer.get() / 65536.0f);
		doubleBuffer.put(intBuffer.get() / 65536.0f);
		doubleBuffer.put(intBuffer.get() / 65536.0f);
		doubleBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glClipPlane(n, doubleBuffer));
	}

	public final synchronized void glGetClipPlanef(final int n, final float[] array, final int n2) {
		final DoubleBuffer doubleBuffer = BufferUtils.createDoubleBuffer(4);
		EGL10Impl.g3d.sync(() -> GL11.glGetClipPlane(n, doubleBuffer));
		array[n2] = (float) doubleBuffer.get(0);
		array[n2 + 1] = (float) doubleBuffer.get(1);
		array[n2 + 2] = (float) doubleBuffer.get(2);
		array[n2 + 3] = (float) doubleBuffer.get(3);
	}

	public final synchronized void glGetClipPlanef(final int n, final FloatBuffer floatBuffer) {
		final DoubleBuffer doubleBuffer = BufferUtils.createDoubleBuffer(4);
		EGL10Impl.g3d.sync(() -> GL11.glGetClipPlane(n, doubleBuffer));
		floatBuffer.put((float) doubleBuffer.get(0));
		floatBuffer.put((float) doubleBuffer.get(1));
		floatBuffer.put((float) doubleBuffer.get(2));
		floatBuffer.put((float) doubleBuffer.get(3));
	}

	public final synchronized void glGetClipPlanex(final int n, final int[] array, final int n2) {
		final DoubleBuffer doubleBuffer = BufferUtils.createDoubleBuffer(4);
		EGL10Impl.g3d.sync(() -> GL11.glGetClipPlane(n, doubleBuffer));
		array[n2] = (int) (doubleBuffer.get(0) * 65536.0);
		array[n2 + 1] = (int) (doubleBuffer.get(1) * 65536.0);
		array[n2 + 2] = (int) (doubleBuffer.get(2) * 65536.0);
		array[n2 + 3] = (int) (doubleBuffer.get(3) * 65536.0);
	}

	public final synchronized void glGetClipPlanex(final int n, final IntBuffer intBuffer) {
		final DoubleBuffer doubleBuffer = BufferUtils.createDoubleBuffer(4);
		EGL10Impl.g3d.sync(() -> GL11.glGetClipPlane(n, doubleBuffer));
		intBuffer.put((int) (doubleBuffer.get(0) * 65536.0));
		intBuffer.put((int) (doubleBuffer.get(1) * 65536.0));
		intBuffer.put((int) (doubleBuffer.get(2) * 65536.0));
		intBuffer.put((int) (doubleBuffer.get(3) * 65536.0));
	}

	public final synchronized void glGetFixedv(final int n, final int[] array, final int n2) {
		// Palette queries
		if (n == OES_MATRIX_PALETTE || n == OES_CURRENT_PALETTE_MATRIX) {
			array[n2] = currentPaletteMatrix;
			return;
		}
		if (n == 2979 || n == 2982) { // modelview stack depth / matrix etc.
			// Fallback to GL
		}
		final int method768 = GLConfiguration.method768(n);
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
		EGL10Impl.g3d.sync(() -> GL11.glGetFloatv(n, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n2 + i] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetFixedv(final int n, final IntBuffer intBuffer) {
		final int method768 = GLConfiguration.method768(n);
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
		EGL10Impl.g3d.sync(() -> GL11.glGetFloatv(n, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glGetFloatv(final int n, final float[] array, final int n2) {
		if (n == 34880) { // GL_MATRIX_PALETTE_OES query -> return current palette matrix
			System.arraycopy(paletteMatrices[currentPaletteMatrix], 0, array, n2, 16);
			return;
		}
		final int method768 = GLConfiguration.method768(n);
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
		EGL10Impl.g3d.sync(() -> GL11.glGetFloatv(n, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n2 + i] = floatBuffer.get(i);
		}
	}

	public final synchronized void glGetFloatv(final int n, final FloatBuffer floatBuffer) {
		if (n == 34880) {
			floatBuffer.put(paletteMatrices[currentPaletteMatrix]);
			return;
		}
		final int method768 = GLConfiguration.method768(n);
		final FloatBuffer floatBuffer2 = BufferUtils.createFloatBuffer(16);
		EGL10Impl.g3d.sync(() -> GL11.glGetFloatv(n, floatBuffer2));
		for (int i = 0; i < method768; ++i) {
			floatBuffer.put(floatBuffer2.get(i));
		}
	}

	public final synchronized void glGetLightfv(final int n, final int n2, final float[] array, final int n3) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetLightfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n3 + i] = floatBuffer.get(i);
		}
	}

	public final synchronized void glGetLightfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetLightfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetLightxv(final int n, final int n2, final int[] array, final int n3) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetLightfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n3 + i] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetLightxv(final int n, final int n2, final IntBuffer intBuffer) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetLightfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glGetMaterialfv(final int n, final int n2, final float[] array, final int n3) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetMaterialfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n3 + i] = floatBuffer.get(i);
		}
	}

	public final synchronized void glGetMaterialfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetMaterialfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetMaterialxv(final int n, final int n2, final int[] array, final int n3) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetMaterialfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			array[n3 + i] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetMaterialxv(final int n, final int n2, final IntBuffer intBuffer) {
		final int method768;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method768 = GLConfiguration.method768(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetMaterialfv(n, n2, floatBuffer));
		for (int i = 0; i < method768; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glGetPointerv(final int n, final Buffer[] array) {
		if (array == null || array.length < 1) {
			throw new IllegalArgumentException();
		}
	}

	public final synchronized void glGetTexEnvfv(final int n, final int n2, final float[] array, final int n3) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnvfv(n, n2, floatBuffer));
		floatBuffer.get(array, n3, method775);
	}

	public final synchronized void glGetTexEnvfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnvfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetTexEnviv(final int n, final int n2, final int[] array, final int n3) {
		final int method775;
		final IntBuffer intBuffer = BufferUtils.createIntBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnviv(n, n2, intBuffer));
		intBuffer.get(array, n3, method775);
	}

	public final synchronized void glGetTexEnviv(final int n, final int n2, final IntBuffer intBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnviv(n, n2, intBuffer));
	}

	public final synchronized void glGetTexEnvxv(final int n, final int n2, final int[] array, final int n3) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnvfv(n, n2, floatBuffer));
		for (int i = 0; i < method775; ++i) {
			array[n3 + i] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetTexEnvxv(final int n, final int n2, final IntBuffer intBuffer) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexEnvfv(n, n2, floatBuffer));
		for (int i = 0; i < method775; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glGetTexParameterfv(final int n, final int n2, final float[] array, final int n3) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameterfv(n, n2, floatBuffer));
		floatBuffer.get(array, n3, method775);
	}

	public final synchronized void glGetTexParameterfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameterfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetTexParameteriv(final int n, final int n2, final int[] array, final int n3) {
		final int method775;
		final IntBuffer intBuffer = BufferUtils.createIntBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameteriv(n, n2, intBuffer));
		intBuffer.get(array, n3, method775);
	}

	public final synchronized void glGetTexParameteriv(final int n, final int n2, final IntBuffer intBuffer) {
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameteriv(n, n2, intBuffer));
	}

	public final synchronized void glGetTexParameterxv(final int n, final int n2, final int[] array, final int n3) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameterfv(n, n2, floatBuffer));
		for (int i = 0; i < method775; ++i) {
			array[n3 + i] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetTexParameterxv(final int n, final int n2, final IntBuffer intBuffer) {
		final int method775;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method775 = GLConfiguration.method775(n2));
		EGL10Impl.g3d.sync(() -> GL11.glGetTexParameterfv(n, n2, floatBuffer));
		for (int i = 0; i < method775; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glDrawTexsOES(final short n, final short n2, final short n3, final short n4, final short n5) {
		this.glDrawTexfOES(n, n2, n3, n4, n5);
	}

	public final synchronized void glDrawTexiOES(final int n, final int n2, final int n3, final int n4, final int n5) {
		this.glDrawTexfOES(n, n2, n3, n4, n5);
	}

	public final synchronized void glDrawTexfOES(final float n, final float n2, final float n3, final float n4, final float n5) {
		if (!GLConfiguration.OES_draw_texture) {
			throw new UnsupportedOperationException("OES_draw_texture extension not available");
		}
	}

	public final synchronized void glDrawTexxOES(final int n, final int n2, final int n3, final int n4, final int n5) {
		this.glDrawTexfOES(n / 65536.0f, n2 / 65536.0f, n3 / 65536.0f, n4 / 65536.0f, n5 / 65536.0f);
	}

	public final synchronized void glDrawTexsvOES(final short[] array, final int n) {
		this.glDrawTexfOES(array[n], array[n + 1], array[n + 2], array[n + 3], array[n + 4]);
	}

	public final synchronized void glDrawTexsvOES(final ShortBuffer shortBuffer) {
		short[] a = new short[5];
		shortBuffer.get(a);
		this.glDrawTexsvOES(a, 0);
	}

	public final synchronized void glDrawTexivOES(final int[] array, final int n) {
		this.glDrawTexfOES(array[n], array[n + 1], array[n + 2], array[n + 3], array[n + 4]);
	}

	public final synchronized void glDrawTexivOES(final IntBuffer intBuffer) {
		int[] a = new int[5];
		intBuffer.get(a);
		this.glDrawTexivOES(a, 0);
	}

	public final synchronized void glDrawTexxvOES(final int[] array, final int n) {
		this.glDrawTexfOES(array[n] / 65536.0f, array[n + 1] / 65536.0f, array[n + 2] / 65536.0f, array[n + 3] / 65536.0f, array[n + 4] / 65536.0f);
	}

	public final synchronized void glDrawTexxvOES(final IntBuffer intBuffer) {
		int[] a = new int[5];
		intBuffer.get(a);
		this.glDrawTexxvOES(a, 0);
	}

	public final synchronized void glDrawTexfvOES(final float[] array, final int n) {
		this.glDrawTexfOES(array[n], array[n + 1], array[n + 2], array[n + 3], array[n + 4]);
	}

	public final synchronized void glDrawTexfvOES(final FloatBuffer floatBuffer) {
		float[] a = new float[5];
		floatBuffer.get(a);
		this.glDrawTexfvOES(a, 0);
	}

	public final synchronized void glCurrentPaletteMatrixOES(final int n) {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		if (n >= 0 && n < paletteMatrices.length) {
			this.currentPaletteMatrix = n;
		}
	}

	public final synchronized void glLoadPaletteFromModelViewMatrixOES() {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		final FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		EGL10Impl.g3d.sync(() -> GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, fb));
		fb.position(0);
		fb.get(paletteMatrices[currentPaletteMatrix]);
	}

	public final synchronized void glMatrixIndexPointerOES(final int n, final int n2, final int n3, final Buffer buffer) {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		this.oesMatrixIndexBuffer = buffer;
		this.oesMatrixIndexSize = n;
		this.oesMatrixIndexType = n2;
		this.oesMatrixIndexStride = n3;
		this.oesMatrixIndexIsOffset = false;
	}

	public final synchronized void glMatrixIndexPointerOES(final int n, final int n2, final int n3, final int n4) {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		this.oesMatrixIndexBuffer = null;
		this.oesMatrixIndexSize = n;
		this.oesMatrixIndexType = n2;
		this.oesMatrixIndexStride = n3;
		this.oesMatrixIndexOffset = n4;
		this.oesMatrixIndexIsOffset = true;
	}

	public final synchronized void glWeightPointerOES(final int n, final int n2, final int n3, final Buffer buffer) {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		this.oesWeightBuffer = buffer;
		this.oesWeightSize = n;
		this.oesWeightType = n2;
		this.oesWeightStride = n3;
		this.oesWeightIsOffset = false;
	}

	public final synchronized void glWeightPointerOES(final int n, final int n2, final int n3, final int n4) {
		if (!GLConfiguration.OES_matrix_pallete) {
			throw new UnsupportedOperationException("OES_matrix_palette extension not available");
		}
		this.oesWeightBuffer = null;
		this.oesWeightSize = n;
		this.oesWeightType = n2;
		this.oesWeightStride = n3;
		this.oesWeightOffset = n4;
		this.oesWeightIsOffset = true;
	}

	private static void checkTextureCubeMapExt() {
		if (!GLConfiguration.OES_texture_cube_map) {
			throw new UnsupportedOperationException("OES_texture_cube_map extension not available");
		}
	}

	public final synchronized void glTexGenf(final int n, final int n2, final float n3) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glTexGenf(n, n2, n3));
	}

	public final synchronized void glTexGeni(final int n, final int n2, final int n3) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glTexGeni(n, n2, n3));
	}

	public final synchronized void glTexGenx(final int n, final int n2, final int n3) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glTexGenf(n, n2, n3 / 65536.0f));
	}

	public final synchronized void glTexGenfv(final int n, final int n2, final float[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer;
		(floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771())).put(array, n3, method771);
		floatBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glTexGenfv(n, n2, floatBuffer));
	}

	public final synchronized void glTexGenfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glTexGenfv(n, n2, floatBuffer));
	}

	public final synchronized void glTexGeniv(final int n, final int n2, final int[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final IntBuffer intBuffer;
		(intBuffer = BufferUtils.createIntBuffer(method771 = GLConfiguration.method771())).put(array, n3, method771);
		intBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glTexGeniv(n, n2, intBuffer));
	}

	public final synchronized void glTexGeniv(final int n, final int n2, final IntBuffer intBuffer) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glTexGeniv(n, n2, intBuffer));
	}

	public final synchronized void glTexGenxv(final int n, final int n2, final int[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771());
		for (int i = 0; i < method771; ++i) {
			floatBuffer.put(array[i] / 65536.0f);
		}
		floatBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glTexGenfv(n, n2, floatBuffer));
	}

	public final synchronized void glTexGenxv(final int n, final int n2, final IntBuffer intBuffer) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771());
		for (int i = 0; i < method771; ++i) {
			floatBuffer.put(intBuffer.get() / 65536.0f);
		}
		floatBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL11.glTexGenfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetTexGenfv(final int n, final int n2, final float[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771());
		EGL10Impl.g3d.sync(() -> GL11.glGetTexGenfv(n, n2, floatBuffer));
		floatBuffer.get(array, n3, method771);
	}

	public final synchronized void glGetTexGenfv(final int n, final int n2, final FloatBuffer floatBuffer) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glGetTexGenfv(n, n2, floatBuffer));
	}

	public final synchronized void glGetTexGeniv(final int n, final int n2, final int[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final IntBuffer intBuffer = BufferUtils.createIntBuffer(method771 = GLConfiguration.method771());
		EGL10Impl.g3d.sync(() -> GL11.glGetTexGeniv(n, n2, intBuffer));
		intBuffer.get(array, n3, method771);
	}

	public final synchronized void glGetTexGeniv(final int n, final int n2, final IntBuffer intBuffer) {
		checkTextureCubeMapExt();
		EGL10Impl.g3d.sync(() -> GL11.glGetTexGeniv(n, n2, intBuffer));
	}

	public final synchronized void glGetTexGenxv(final int n, final int n2, final int[] array, final int n3) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771());
		EGL10Impl.g3d.sync(() ->GL11.glGetTexGenfv(n, n2, floatBuffer));
		for (int i = 0; i < method771; ++i) {
			array[i + n3] = (int) (floatBuffer.get(i) * 65536.0f);
		}
	}

	public final synchronized void glGetTexGenxv(final int n, final int n2, final IntBuffer intBuffer) {
		checkTextureCubeMapExt();
		final int method771;
		final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(method771 = GLConfiguration.method771());
		EGL10Impl.g3d.sync(() ->GL11.glGetTexGenfv(n, n2, floatBuffer));
		for (int i = 0; i < method771; ++i) {
			intBuffer.put((int) (floatBuffer.get(i) * 65536.0f));
		}
	}

	public final synchronized void glBlendEquation(final int n) {
		if (!GLConfiguration.OES_blend_subtract) {
			throw new UnsupportedOperationException("OES_blend_subtract extension not available");
		}
		EGL10Impl.g3d.sync(() -> GL14.glBlendEquation(n));
	}

	public final synchronized void glBlendFuncSeparate(final int n, final int n2, final int n3, final int n4) {
		if (!GLConfiguration.OES_blend_func_separate) {
			throw new UnsupportedOperationException("OES_blend_func_separate extension not available");
		}
		EGL10Impl.g3d.sync(() -> GL14.glBlendFuncSeparate(n, n2, n3, n4));
	}

	public final synchronized void glBlendEquationSeparate(final int n, final int n2) {
		if (!GLConfiguration.OES_blend_equations_separate) {
			throw new UnsupportedOperationException("OES_blend_equations_separate extension not available");
		}
		EGL10Impl.g3d.sync(() -> GL20.glBlendEquationSeparate(n, n2));
	}

	private void checkFramebufferExt() {
		if (!GLConfiguration.OES_framebuffer_object) {
			throw new UnsupportedOperationException("OES_framebuffer_object extension not available");
		}
	}

	public final synchronized boolean glIsRenderbufferOES(final int n) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> temp = GL30.glIsRenderbuffer(n));
		return (boolean) temp;
	}

	public final synchronized void glBindRenderbufferOES(final int n, final int n2) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glBindRenderbuffer(n, n2));
	}

	public final synchronized void glDeleteRenderbuffersOES(final int n, final int[] array, final int n2) {
		checkFramebufferExt();
		IntBuffer intBuffer = BufferUtils.createIntBuffer(n);
		intBuffer.put(array, n2, n);
		intBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL30.glDeleteRenderbuffers(intBuffer));
	}

	public final synchronized void glDeleteRenderbuffersOES(final int n, final IntBuffer intBuffer) {
		checkFramebufferExt();
		int l = intBuffer.limit();
		intBuffer.limit(n + intBuffer.position());
		EGL10Impl.g3d.sync(() -> GL30.glDeleteRenderbuffers(intBuffer));
		intBuffer.limit(l);
	}

	public final synchronized void glGenRenderbuffersOES(final int n, final int[] array, final int n2) {
		checkFramebufferExt();
		IntBuffer intBuffer = BufferUtils.createIntBuffer(n);
		EGL10Impl.g3d.sync(() -> GL30.glGenRenderbuffers(intBuffer));
		intBuffer.get(array, n2, n);
	}

	public final synchronized void glGenRenderbuffersOES(final int n, final IntBuffer intBuffer) {
		checkFramebufferExt();
		int l = intBuffer.limit();
		intBuffer.limit(n + intBuffer.position());
		EGL10Impl.g3d.sync(() -> GL30.glGenRenderbuffers(intBuffer));
		intBuffer.limit(l);
	}

	public final synchronized void glRenderbufferStorageOES(final int n, final int n2, final int n3, final int n4) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glRenderbufferStorage(n, n2, n3, n4));
	}

	public final synchronized void glGetRenderbufferParameterivOES(final int n, final int n2, final int[] array, final int n3) {
		checkFramebufferExt();
		int length = 1;
		IntBuffer intBuffer = BufferUtils.createIntBuffer(length);
		EGL10Impl.g3d.sync(() -> GL30.glGetRenderbufferParameteriv(n, n2, intBuffer));
		intBuffer.get(array, n, length);
	}

	public final synchronized void glGetRenderbufferParameterivOES(final int n, final int n2, final IntBuffer intBuffer) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glGetRenderbufferParameteriv(n, n2, intBuffer));
	}

	public final synchronized boolean glIsFramebufferOES(final int n) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> temp = GL30.glIsFramebuffer(n));
		return (boolean) temp;
	}

	public final synchronized void glBindFramebufferOES(final int n, final int n2) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glBindFramebuffer(n, n2));
	}

	public final synchronized void glDeleteFramebuffersOES(final int n, final int[] array, final int n2) {
		checkFramebufferExt();
		IntBuffer intBuffer = BufferUtils.createIntBuffer(n);
		intBuffer.put(array, n2, n);
		intBuffer.position(0);
		EGL10Impl.g3d.sync(() -> GL30.glDeleteFramebuffers(intBuffer));
	}

	public final synchronized void glDeleteFramebuffersOES(final int n, final IntBuffer intBuffer) {
		checkFramebufferExt();
		int l = intBuffer.limit();
		intBuffer.limit(n + intBuffer.position());
		EGL10Impl.g3d.sync(() -> GL30.glDeleteFramebuffers(intBuffer));
		intBuffer.limit(l);
	}

	public final synchronized void glGenFramebuffersOES(final int n, final int[] array, final int n2) {
		checkFramebufferExt();
		IntBuffer intBuffer = BufferUtils.createIntBuffer(n);
		EGL10Impl.g3d.sync(() -> GL30.glGenFramebuffers(intBuffer));
		intBuffer.get(array, n2, n);
	}

	public final synchronized void glGenFramebuffersOES(final int n, final IntBuffer intBuffer) {
		checkFramebufferExt();
		int l = intBuffer.limit();
		intBuffer.limit(n + intBuffer.position());
		EGL10Impl.g3d.sync(() -> GL30.glGenFramebuffers(intBuffer));
		intBuffer.limit(l);
	}

	public final synchronized int glCheckFramebufferStatusOES(final int n) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> temp = GL30.glCheckFramebufferStatus(n));
		return (int) temp;
	}

	public final synchronized void glFramebufferTexture2DOES(final int n, final int n2, final int n3, final int n4, final int n5) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glFramebufferTexture2D(n, n2, n3, n4, n5));
	}

	public final synchronized void glFramebufferRenderbufferOES(final int n, final int n2, final int n3, final int n4) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glFramebufferRenderbuffer(n, n2, n3, n4));
	}

	public final synchronized void glGetFramebufferAttachmentParameterivOES(final int n, final int n2, final int n3, final int[] array, final int n4) {
		checkFramebufferExt();
		int length = 1;
		IntBuffer intBuffer = BufferUtils.createIntBuffer(length);
		EGL10Impl.g3d.sync(() -> GL30.glGetFramebufferAttachmentParameteriv(n, n2, n3, intBuffer));
		intBuffer.get(array, n, length);
	}

	public final synchronized void glGetFramebufferAttachmentParameterivOES(final int n, final int n2, final int n3, final IntBuffer intBuffer) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glGetFramebufferAttachmentParameteriv(n, n2, n3, intBuffer));
	}

	public final synchronized void glGenerateMipmapOES(final int n) {
		checkFramebufferExt();
		EGL10Impl.g3d.sync(() -> GL30.glGenerateMipmap(n));
	}

	public final synchronized void glPointSizePointerOES(final int n, final int n2, final Buffer buffer) {
		System.out.println("OES is not implemented.");
	}

	public final synchronized void glPointSizePointerOES(final int n, final int n2, final int n3) {
		System.out.println("OES is not implemented.");
	}

	// --------------------------------------------------------------------
	// Matrix helpers
	private static void identity(float[] m) {
		for (int i = 0; i < 16; i++) m[i] = 0f;
		m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f;
	}

	private static void mulMatMat(final float[] a, final float[] b, final float[] out) {
		// out = a * b, column-major
		for (int row = 0; row < 4; row++) {
			float a0 = a[row];
			float a1 = a[4 + row];
			float a2 = a[8 + row];
			float a3 = a[12 + row];
			out[row] = a0 * b[0] + a1 * b[1] + a2 * b[2] + a3 * b[3];
			out[4 + row] = a0 * b[4] + a1 * b[5] + a2 * b[6] + a3 * b[7];
			out[8 + row] = a0 * b[8] + a1 * b[9] + a2 * b[10] + a3 * b[11];
			out[12 + row] = a0 * b[12] + a1 * b[13] + a2 * b[14] + a3 * b[15];
		}
	}

	private static void translateMat(final float[] m, float x, float y, float z) {
		// m = m * T
		m[12] = m[0] * x + m[4] * y + m[8] * z + m[12];
		m[13] = m[1] * x + m[5] * y + m[9] * z + m[13];
		m[14] = m[2] * x + m[6] * y + m[10] * z + m[14];
		m[15] = m[3] * x + m[7] * y + m[11] * z + m[15];
	}

	private static void scaleMat(final float[] m, float x, float y, float z) {
		m[0] *= x; m[1] *= x; m[2] *= x; m[3] *= x;
		m[4] *= y; m[5] *= y; m[6] *= y; m[7] *= y;
		m[8] *= z; m[9] *= z; m[10] *= z; m[11] *= z;
	}

	private static void rotateMat(final float[] m, float angleDeg, float ax, float ay, float az) {
		float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		if (len == 0f) return;
		if (len != 1f) { ax /= len; ay /= len; az /= len; }
		float rad = (float) Math.toRadians(angleDeg);
		float c = (float) Math.cos(rad);
		float s = (float) Math.sin(rad);
		float t = 1f - c;
		float[] r = new float[16];
		r[0] = t * ax * ax + c;
		r[1] = t * ax * ay + az * s;
		r[2] = t * ax * az - ay * s;
		r[3] = 0f;
		r[4] = t * ax * ay - az * s;
		r[5] = t * ay * ay + c;
		r[6] = t * ay * az + ax * s;
		r[7] = 0f;
		r[8] = t * ax * az + ay * s;
		r[9] = t * ay * az - ax * s;
		r[10] = t * az * az + c;
		r[11] = 0f;
		r[12] = 0f; r[13] = 0f; r[14] = 0f; r[15] = 1f;
		float[] res = new float[16];
		mulMatMat(m, r, res);
		System.arraycopy(res, 0, m, 0, 16);
	}

	private static void makeFrustum(float[] m, float l, float r, float b, float t, float n, float f) {
		identity(m);
		float rl = r - l;
		float tb = t - b;
		float fn = f - n;
		if (rl == 0 || tb == 0 || fn == 0) return;
		m[0] = 2f * n / rl;
		m[5] = 2f * n / tb;
		m[8] = (r + l) / rl;
		m[9] = (t + b) / tb;
		m[10] = -(f + n) / fn;
		m[11] = -1f;
		m[14] = -2f * f * n / fn;
		m[15] = 0f;
	}

	private static void makeOrtho(float[] m, float l, float r, float b, float t, float n, float f) {
		identity(m);
		float rl = r - l;
		float tb = t - b;
		float fn = f - n;
		if (rl == 0 || tb == 0 || fn == 0) return;
		m[0] = 2f / rl;
		m[5] = 2f / tb;
		m[10] = -2f / fn;
		m[12] = -(r + l) / rl;
		m[13] = -(t + b) / tb;
		m[14] = -(f + n) / fn;
	}

	// ---- Skinning helpers ----
	private static int bytesPerType(int type) {
		switch (type) {
			case GL_BYTE:
			case GL_UNSIGNED_BYTE:
				return 1;
			case GL_SHORT:
			case GL_UNSIGNED_SHORT:
				return 2;
			case GL_FIXED:
			case GL_FLOAT:
			default:
				return 4;
		}
	}

	private static float readFloatComponent(Buffer buf, int byteOffset, int type) {
		if (buf instanceof ByteBuffer) {
			ByteBuffer bb = (ByteBuffer) buf;
			int pos = bb.position() + byteOffset;
			switch (type) {
				case GL_BYTE: return bb.get(pos);
				case GL_UNSIGNED_BYTE: return bb.get(pos) & 0xFF;
				case GL_SHORT: return bb.getShort(pos);
				case GL_UNSIGNED_SHORT: return bb.getShort(pos) & 0xFFFF;
				case GL_FIXED: return bb.getInt(pos) / 65536.0f;
				case GL_FLOAT: default: return bb.getFloat(pos);
			}
		} else if (buf instanceof FloatBuffer) {
			FloatBuffer fb = (FloatBuffer) buf;
			int idx = fb.position() + byteOffset / 4;
			switch (type) {
				case GL_FIXED: {
					// FloatBuffer shouldn't contain fixed, but handle: float bits?
					int intBits = Float.floatToRawIntBits(fb.get(idx));
					return intBits / 65536.0f;
				}
				case GL_BYTE:
				case GL_UNSIGNED_BYTE:
				case GL_SHORT:
				case GL_UNSIGNED_SHORT:
					return fb.get(idx);
				case GL_FLOAT:
				default:
					return fb.get(idx);
			}
		} else if (buf instanceof ShortBuffer) {
			ShortBuffer sb = (ShortBuffer) buf;
			int idx = sb.position() + byteOffset / 2;
			short v = sb.get(idx);
			switch (type) {
				case GL_BYTE: return (byte) v;
				case GL_UNSIGNED_BYTE: return v & 0xFF;
				case GL_SHORT: return v;
				case GL_UNSIGNED_SHORT: return v & 0xFFFF;
				case GL_FIXED: return v / 4096.0f; // not accurate, but fallback
				case GL_FLOAT:
				default: return v;
			}
		} else if (buf instanceof IntBuffer) {
			IntBuffer ib = (IntBuffer) buf;
			int idx = ib.position() + byteOffset / 4;
			int v = ib.get(idx);
			switch (type) {
				case GL_FIXED: return v / 65536.0f;
				default: return v;
			}
		}
		return 0f;
	}

	private static void readIndices(final Buffer buf, final int vertexIndex, final int indexSize, final int strideBytes, final int type, final int[] out) {
		if (buf == null) return;
		int bpe;
		if (type == GL_UNSIGNED_BYTE) bpe = 1;
		else if (type == GL_UNSIGNED_SHORT) bpe = 2;
		else bpe = 2;
		int stride = (strideBytes == 0) ? indexSize * bpe : strideBytes;
		int baseByteOffset = vertexIndex * stride;
		for (int i = 0; i < indexSize; i++) {
			int byteOffset = baseByteOffset + i * bpe;
			float f = readFloatComponent(buf, byteOffset, type);
			out[i] = (int) f;
			if (type == GL_UNSIGNED_BYTE) out[i] &= 0xFF;
			else if (type == GL_UNSIGNED_SHORT) out[i] &= 0xFFFF;
		}
	}

	private static void readWeights(final Buffer buf, final int vertexIndex, final int weightSize, final int strideBytes, final int type, final float[] out) {
		if (buf == null) return;
		int bpe = bytesPerType(type);
		int stride = (strideBytes == 0) ? weightSize * bpe : strideBytes;
		int base = vertexIndex * stride;
		for (int i = 0; i < weightSize; i++) {
			int byteOff = base + i * bpe;
			out[i] = readFloatComponent(buf, byteOff, type);
		}
	}

	private static void readVertexComp(final Buffer buf, final int vertexIndex, final int size, final int strideBytes, final int type, final float[] outVec4) {
		int bpe = bytesPerType(type);
		int stride = (strideBytes == 0) ? size * bpe : strideBytes;
		int base = vertexIndex * stride;
		outVec4[0] = 0f; outVec4[1] = 0f; outVec4[2] = 0f; outVec4[3] = 1f;
		for (int i = 0; i < size && i < 4; i++) {
			int byteOff = base + i * bpe;
			outVec4[i] = readFloatComponent(buf, byteOff, type);
		}
	}

	private static int readElementIndex(Buffer buf, int elementPos, int type) {
		int bpe = (type == GL_UNSIGNED_BYTE) ? 1 : 2;
		int byteOff = elementPos * bpe;
		float f = readFloatComponent(buf, byteOff, type);
		int v = (int) f;
		if (type == GL_UNSIGNED_BYTE) return v & 0xFF;
		return v & 0xFFFF;
	}

	private static void mulMat4Vec4(final float[] m, final float[] v, final float[] out) {
		for (int row = 0; row < 4; row++) {
			out[row] = m[row] * v[0] + m[4 + row] * v[1] + m[8 + row] * v[2] + m[12 + row] * v[3];
		}
	}

	private static void mulMat3Vec3(final float[] m, final float[] v, final float[] out) {
		out[0] = m[0] * v[0] + m[4] * v[1] + m[8] * v[2];
		out[1] = m[1] * v[0] + m[5] * v[1] + m[9] * v[2];
		out[2] = m[2] * v[0] + m[6] * v[1] + m[10] * v[2];
	}

	private static void normalizeVec3(final float[] v) {
		float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (len != 0.0f) {
			v[0] /= len;
			v[1] /= len;
			v[2] /= len;
		}
	}

	private boolean trySoftwareSkinAndDrawArrays(final int mode, final int first, final int count) {
		if (!this.oesMatrixPaletteEnabled || !this.oesMatrixIndexArrayEnabled || !this.oesWeightArrayEnabled) return false;
		if (this.vertexBuffer == null) return false;
		if (this.oesMatrixIndexIsOffset || this.oesWeightIsOffset || this.vertexIsOffset || (normalArrayEnabled && normalIsOffset)) {
			return false;
		}
		if (this.oesMatrixIndexType != GL_UNSIGNED_BYTE && this.oesMatrixIndexType != GL_UNSIGNED_SHORT) return false;

		final int vSize = this.vertexSize;
		final FloatBuffer skinnedVerts = BufferUtils.createFloatBuffer(count * vSize);

		final FloatBuffer skinnedNormals = normalArrayEnabled ? BufferUtils.createFloatBuffer(count * 3) : null;
		final float[] origN = normalArrayEnabled ? new float[3] : null;
		final float[] tmpN = normalArrayEnabled ? new float[3] : null;
		final float[] accumN = normalArrayEnabled ? new float[3] : null;

		final int[] indices = new int[this.oesMatrixIndexSize];
		final float[] weights = new float[this.oesWeightSize];
		final float[] origV = new float[4];
		final float[] tmpV = new float[4];
		final float[] accumV = new float[4];

		for (int vi = 0; vi < count; vi++) {
			final int vertIndex = first + vi;
			accumV[0] = 0f; accumV[1] = 0f; accumV[2] = 0f; accumV[3] = 0f;
			readVertexComp(this.vertexBuffer, vertIndex, vSize, this.vertexStride, this.vertexType, origV);

			if (normalArrayEnabled) {
				accumN[0] = 0f; accumN[1] = 0f; accumN[2] = 0f;
				float[] nTmp = new float[4];
				readVertexComp(this.normalBuffer, vertIndex, 3, this.normalStride, this.normalType, nTmp);
				origN[0] = nTmp[0]; origN[1] = nTmp[1]; origN[2] = nTmp[2];
			}

			readIndices(this.oesMatrixIndexBuffer, vertIndex, this.oesMatrixIndexSize, this.oesMatrixIndexStride, this.oesMatrixIndexType, indices);
			readWeights(this.oesWeightBuffer, vertIndex, this.oesWeightSize, this.oesWeightStride, this.oesWeightType, weights);

			for (int k = 0; k < this.oesWeightSize; k++) {
				final int mi = indices[k];
				if (mi < 0 || mi >= paletteMatrices.length) continue;
				final float[] matrix = paletteMatrices[mi];
				final float w = weights[k];
				mulMat4Vec4(matrix, origV, tmpV);
				accumV[0] += tmpV[0] * w;
				accumV[1] += tmpV[1] * w;
				accumV[2] += tmpV[2] * w;
				accumV[3] += tmpV[3] * w;
				if (normalArrayEnabled) {
					mulMat3Vec3(matrix, origN, tmpN);
					accumN[0] += tmpN[0] * w;
					accumN[1] += tmpN[1] * w;
					accumN[2] += tmpN[2] * w;
				}
			}
			for (int a = 0; a < vSize; a++) skinnedVerts.put(accumV[a]);
			if (normalArrayEnabled) {
				normalizeVec3(accumN);
				skinnedNormals.put(accumN);
			}
		}
		skinnedVerts.position(0);
		if (skinnedNormals != null) skinnedNormals.position(0);

		EGL10Impl.g3d.sync(() -> {
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			GL11.glPushMatrix();
			GL11.glLoadIdentity();
			GL11.glVertexPointer(this.vertexSize, GL11.GL_FLOAT, 0, skinnedVerts);
			if (normalArrayEnabled && skinnedNormals != null) {
				GL11.glNormalPointer(GL11.GL_FLOAT, 0, skinnedNormals);
			}
			GL11.glDrawArrays(mode, 0, count);
			GL11.glPopMatrix();
			// restore
			if (this.vertexBuffer != null) {
				GL11.glVertexPointer(this.vertexSize, this.vertexType, this.vertexStride, MemoryUtil.memAddress(this.vertexBuffer));
			}
			if (normalArrayEnabled && this.normalBuffer != null) {
				GL11.glNormalPointer(this.normalType, this.normalStride, MemoryUtil.memAddress(this.normalBuffer));
			}
		});
		return true;
	}

	private boolean trySoftwareSkinAndDrawElements(final int mode, final int count, final int type, final Buffer indicesBuffer) {
		if (!this.oesMatrixPaletteEnabled || !this.oesMatrixIndexArrayEnabled || !this.oesWeightArrayEnabled) return false;
		if (this.vertexBuffer == null) return false;
		if (this.oesMatrixIndexIsOffset || this.oesWeightIsOffset || this.vertexIsOffset || (normalArrayEnabled && normalIsOffset)) {
			return false;
		}
		if (this.oesMatrixIndexType != GL_UNSIGNED_BYTE && this.oesMatrixIndexType != GL_UNSIGNED_SHORT) return false;
		if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT) return false;

		final int vSize = this.vertexSize;
		final FloatBuffer skinnedVerts = BufferUtils.createFloatBuffer(count * vSize);
		final FloatBuffer skinnedNormals = normalArrayEnabled ? BufferUtils.createFloatBuffer(count * 3) : null;
		final float[] origN = normalArrayEnabled ? new float[3] : null;
		final float[] tmpN = normalArrayEnabled ? new float[3] : null;
		final float[] accumN = normalArrayEnabled ? new float[3] : null;

		final int[] indices = new int[this.oesMatrixIndexSize];
		final float[] weights = new float[this.oesWeightSize];
		final float[] origV = new float[4];
		final float[] tmpV = new float[4];
		final float[] accumV = new float[4];

		for (int ei = 0; ei < count; ei++) {
			int vertexIndex = readElementIndex(indicesBuffer, ei, type);

			accumV[0] = 0f; accumV[1] = 0f; accumV[2] = 0f; accumV[3] = 0f;
			readVertexComp(this.vertexBuffer, vertexIndex, vSize, this.vertexStride, this.vertexType, origV);

			if (normalArrayEnabled) {
				accumN[0] = 0f; accumN[1] = 0f; accumN[2] = 0f;
				float[] nTmp = new float[4];
				readVertexComp(this.normalBuffer, vertexIndex, 3, this.normalStride, this.normalType, nTmp);
				origN[0] = nTmp[0]; origN[1] = nTmp[1]; origN[2] = nTmp[2];
			}

			readIndices(this.oesMatrixIndexBuffer, vertexIndex, this.oesMatrixIndexSize, this.oesMatrixIndexStride, this.oesMatrixIndexType, indices);
			readWeights(this.oesWeightBuffer, vertexIndex, this.oesWeightSize, this.oesWeightStride, this.oesWeightType, weights);

			for (int k = 0; k < this.oesWeightSize; k++) {
				final int mi = indices[k];
				if (mi < 0 || mi >= paletteMatrices.length) continue;
				final float[] matrix = paletteMatrices[mi];
				final float w = weights[k];
				mulMat4Vec4(matrix, origV, tmpV);
				accumV[0] += tmpV[0] * w;
				accumV[1] += tmpV[1] * w;
				accumV[2] += tmpV[2] * w;
				accumV[3] += tmpV[3] * w;
				if (normalArrayEnabled) {
					mulMat3Vec3(matrix, origN, tmpN);
					accumN[0] += tmpN[0] * w;
					accumN[1] += tmpN[1] * w;
					accumN[2] += tmpN[2] * w;
				}
			}
			for (int a = 0; a < vSize; a++) skinnedVerts.put(accumV[a]);
			if (normalArrayEnabled) {
				normalizeVec3(accumN);
				skinnedNormals.put(accumN);
			}
		}
		skinnedVerts.position(0);
		if (skinnedNormals != null) skinnedNormals.position(0);

		EGL10Impl.g3d.sync(() -> {
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			GL11.glPushMatrix();
			GL11.glLoadIdentity();
			GL11.glVertexPointer(this.vertexSize, GL11.GL_FLOAT, 0, skinnedVerts);
			if (normalArrayEnabled && skinnedNormals != null) {
				GL11.glNormalPointer(GL11.GL_FLOAT, 0, skinnedNormals);
			}
			GL11.glDrawArrays(mode, 0, count);
			GL11.glPopMatrix();
			if (this.vertexBuffer != null) {
				GL11.glVertexPointer(this.vertexSize, this.vertexType, this.vertexStride, MemoryUtil.memAddress(this.vertexBuffer));
			}
			if (normalArrayEnabled && this.normalBuffer != null) {
				GL11.glNormalPointer(this.normalType, this.normalStride, MemoryUtil.memAddress(this.normalBuffer));
			}
		});
		return true;
	}

	public synchronized void glDrawArrays(final int mode, final int first, final int count) {
		if (!trySoftwareSkinAndDrawArrays(mode, first, count)) {
			EGL10Impl.g3d.sync(() -> GL11.glDrawArrays(mode, first, count));
		}
	}

	public synchronized void glDrawElements(final int mode, final int count, final int type, final Buffer indices) {
		if (!trySoftwareSkinAndDrawElements(mode, count, type, indices)) {
			EGL10Impl.g3d.sync(() -> GL11.glDrawElements(mode, count, type, MemoryUtil.memAddress(indices)));
		}
	}

	public synchronized void glDrawElements(final int mode, final int count, final int type, final int indicesOffset) {
		EGL10Impl.g3d.sync(() -> GL11.glDrawElements(mode, count, type, indicesOffset));
	}

	public synchronized void glDisable(final int n) {
		if (n == OES_MATRIX_PALETTE) {
			this.oesMatrixPaletteEnabled = false;
			return;
		}
		if (n == 2896) {
			GL10Impl.aBoolean1355 = true;
		} else if (n == 2912) {
			GL10Impl.aBoolean1358 = true;
		}
		EGL10Impl.g3d.async(() -> GL11.glDisable(n));
	}

	public synchronized void glDisableClientState(final int n) {
		if (n == OES_MATRIX_INDEX_ARRAY) {
			this.oesMatrixIndexArrayEnabled = false;
			return;
		}
		if (n == OES_WEIGHT_ARRAY) {
			this.oesWeightArrayEnabled = false;
			return;
		}
		if (n == GL11.GL_NORMAL_ARRAY) {
			this.normalArrayEnabled = false;
		}
		EGL10Impl.g3d.async(() -> GL11.glDisableClientState(n));
	}

	public synchronized void glEnable(final int n) {
		if (n == OES_MATRIX_PALETTE) {
			this.oesMatrixPaletteEnabled = true;
			return;
		}
		if (n == 2896) {
			GL10Impl.aBoolean1355 = true;
		} else if (n == 2912) {
			GL10Impl.aBoolean1358 = true;
		}
		EGL10Impl.g3d.async(() -> GL11.glEnable(n));
	}

	public synchronized void glEnableClientState(final int n) {
		if (n == OES_MATRIX_INDEX_ARRAY) {
			this.oesMatrixIndexArrayEnabled = true;
			return;
		}
		if (n == OES_WEIGHT_ARRAY) {
			this.oesWeightArrayEnabled = true;
			return;
		}
		if (n == GL11.GL_NORMAL_ARRAY) {
			this.normalArrayEnabled = true;
		}
		EGL10Impl.g3d.async(() -> GL11.glEnableClientState(n));
	}

	public GL11Impl(final EGLContext eglContext) {
		super(eglContext);
		for (int i = 0; i < paletteMatrices.length; i++) identity(paletteMatrices[i]);
	}
}
