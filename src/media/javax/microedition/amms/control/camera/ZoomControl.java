package javax.microedition.amms.control.camera;

import javax.microedition.media.Control;

/**
 * ZoomControl allows the user to control the zooming of the camera.
 */
public interface ZoomControl extends Control {
	int NEXT = -1001;
	int PREVIOUS = -1002;
	int UNKNOWN = -1004;

	int setOpticalZoom(int zoom);

	int getOpticalZoom();

	int getMaxOpticalZoom();

	int getOpticalZoomLevels();

	int getMinFocalLength();

	int setDigitalZoom(int zoom);

	int getDigitalZoom();

	int getMaxDigitalZoom();

	int getDigitalZoomLevels();
}
