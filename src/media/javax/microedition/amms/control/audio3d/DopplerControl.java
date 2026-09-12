package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * DopplerControl is an interface for enabling and setting the velocity
 * of an object for the Doppler effect. Velocities are specified in
 * millimeters per second.
 */
public interface DopplerControl extends Control {

	int[] getVelocityCartesian();

	void setVelocityCartesian(int x, int y, int z);

	void setVelocitySpherical(int azimuth, int elevation, int radius);

	boolean isEnabled();

	void setEnabled(boolean enabled);
}
