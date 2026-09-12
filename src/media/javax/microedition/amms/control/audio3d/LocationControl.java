package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * LocationControl is an interface for manipulating the virtual location
 * of an object in the virtual acoustical space. The location is specified
 * in millimeters relative to the Spectator.
 */
public interface LocationControl extends Control {

	int[] getCartesian();

	void setCartesian(int x, int y, int z);

	void setSpherical(int magnitude, int azimuth, int polar);
}
