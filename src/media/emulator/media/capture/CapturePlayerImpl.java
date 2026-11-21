package emulator.media.capture;

import emulator.Settings;
import emulator.Utils;
import emulator.media.vlc.VLCPlayerImpl;

import javax.microedition.media.Control;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** MMAPI camera controls backed by the same VLC player as regular video. */
public final class CapturePlayerImpl extends VLCPlayerImpl {
	private final Control focus = new FocusControlImpl();
	private final Control flash = new FlashControlImpl();
	private final Control camera = new CameraControlImpl();

	public CapturePlayerImpl(String locator) throws IOException, MediaException {
		super(captureInput(locator), "video/raw", Utils.win ? new String[]{":dshow-adev=none"} : new String[0]);
	}

	public static boolean isCaptureEnabled() {
		return !Settings.disableCamera && System.getProperty("kemulator.disablecamera") == null
				&& (Utils.win || Utils.linux || Utils.macos);
	}

	public static boolean isCaptureSupported() {
		return isCaptureEnabled() && (!Utils.linux || new File("/dev/video0").exists())
				&& Manager._isLibVlcSupported();
	}

	private static String captureInput(String locator) throws MediaException {
		if (!isCaptureEnabled()) throw new MediaException("Camera capture is disabled or unsupported on this platform");
		boolean secondCamera = locator.startsWith("capture://devcam1");
		if (Utils.win) {
			if (secondCamera) throw new MediaException("Only the default DirectShow camera is supported");
			return "dshow://";
		}
		if (Utils.macos) {
			if (secondCamera) throw new MediaException("Only the default AVFoundation camera is supported");
			return "avcapture://";
		}
		return secondCamera ? "v4l2:///dev/video1" : "v4l2:///dev/video0";
	}

	@Override
	public Control getControl(String name) {
		if (name.endsWith("FocusControl")) return focus;
		if (name.endsWith("FlashControl")) return flash;
		if (name.endsWith("CameraControl")) return camera;
		return super.getControl(name);
	}

	@Override
	public Control[] getControls() {
		Control[] controls = super.getControls();
		int n = controls.length;
		controls = Arrays.copyOf(controls, n + 3);
		controls[n] = focus;
		controls[n + 1] = flash;
		controls[n + 2] = camera;
		return controls;
	}
}
