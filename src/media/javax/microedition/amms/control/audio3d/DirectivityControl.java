package javax.microedition.amms.control.audio3d;

/**
 * DirectivityControl adds to OrientationControl a method for setting the
 * directivity pattern of a sound source.
 */
public interface DirectivityControl extends OrientationControl {

	int[] getParameters();

	void setParameters(int minAngle, int maxAngle, int rearLevel);
}
