package javax.microedition.amms.control.camera;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * FocusControl controls the focus of the camera.
 */
public interface FocusControl extends Control {
	int AUTO = -1000;
	int NEXT = -1001;
	int PREVIOUS = -1002;
	int UNKNOWN = -1004;
	int AUTO_LOCK = -1005;

	int setFocus(int focus) throws MediaException;

	int getFocus();

	int getMinFocus();

	int getFocusSteps();

	boolean isManualFocusSupported();

	boolean isAutoFocusSupported();

	boolean isMacroSupported();

	void setMacro(boolean state) throws MediaException;

	boolean getMacro();
}
