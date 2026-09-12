package javax.microedition.amms.control.camera;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * CameraControl controls the features of the camera device.
 */
public interface CameraControl extends Control {
	int ROTATE_NONE = 1;
	int ROTATE_LEFT = 2;
	int ROTATE_RIGHT = 3;
	int UNKNOWN = -1004;

	int getCameraRotation();

	void enableShutterFeedback(boolean enable) throws MediaException;

	boolean isShutterFeedbackEnabled();

	String[] getSupportedExposureModes();

	void setExposureMode(String mode);

	String getExposureMode();

	int[] getSupportedVideoResolutions();

	int[] getSupportedStillResolutions();

	void setVideoResolution(int index);

	void setStillResolution(int index);

	int getVideoResolution();

	int getStillResolution();
}
