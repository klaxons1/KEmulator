package javax.microedition.amms.control.camera;

import javax.microedition.media.Control;

/**
 * SnapshotControl allows the user to control the capturing of images
 * (snapshots) from the camera.
 */
public interface SnapshotControl extends Control {
	String SHOOTING_STOPPED = "SHOOTING_STOPPED";
	String STORAGE_ERROR = "STORAGE_ERROR";
	String WAITING_UNFREEZE = "WAITING_UNFREEZE";
	int FREEZE = -2;
	int FREEZE_AND_CONFIRM = -1;

	void setDirectory(String dir);

	String getDirectory();

	void setFilePrefix(String prefix);

	String getFilePrefix();

	void setFileSuffix(String suffix);

	String getFileSuffix();

	void start(int delay) throws SecurityException;

	void stop();

	void unfreeze(boolean freeze);
}
