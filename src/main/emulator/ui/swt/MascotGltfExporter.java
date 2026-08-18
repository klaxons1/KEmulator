package emulator.ui.swt;

import emulator.Settings;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * UI-side entry point for the Mascot Capsule glTF ripper.
 * The implementation lives in the MascotME module
 * ({@code com.mascotcapsule.micro3d.v3.MascotGltfExporter}) and is invoked
 * through reflection so KEmulator_base stays independent of the selected
 * Micro3D backend.
 */
public final class MascotGltfExporter {

	private static final String IMPL = "com.mascotcapsule.micro3d.v3.MascotGltfExporter";

	private MascotGltfExporter() {}

	public static boolean isAvailable() {
		return Settings.micro3d == 2;
	}

	public static boolean hasScene() {
		if (!isAvailable()) {
			return false;
		}
		try {
			Class<?> cls = Class.forName(IMPL);
			Boolean result = (Boolean) cls.getMethod("hasScene").invoke(null);
			return Boolean.TRUE.equals(result);
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static void export(File outFile) throws IOException {
		if (outFile == null) {
			throw new NullPointerException("outFile");
		}
		try {
			Class<?> cls = Class.forName(IMPL);
			cls.getMethod("export", File.class).invoke(null, outFile);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof IOException) {
				throw (IOException) cause;
			}
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new IOException(cause.getMessage(), cause);
		} catch (ClassNotFoundException e) {
			throw new IOException("MascotME exporter is not available (enable Software / MascotME)", e);
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}
