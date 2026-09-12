package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * OrientationControl is an interface for manipulating the virtual
 * orientation of an object in the virtual acoustical space.
 */
public interface OrientationControl extends Control {

	int[] getOrientationVectors();

	void setOrientation(int[] frontVector, int[] aboveVector) throws IllegalArgumentException;

	void setOrientation(int heading, int pitch, int roll);
}
