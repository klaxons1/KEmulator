package emulator;

public final class NativeLibraryLoader {

	private NativeLibraryLoader() {
	}

	// loadLibrary win32
	public static void loadWin32Library(final String libraryName) {
		if (Emulator.isX64()) throw new UnsatisfiedLinkError("x64 version!");
		try {
			System.load(Emulator.getAbsolutePath() + "/" + libraryName + ".dll");
		} catch (UnsatisfiedLinkError e) {
			e.printStackTrace();
			System.loadLibrary(libraryName);
		}
	}
}
