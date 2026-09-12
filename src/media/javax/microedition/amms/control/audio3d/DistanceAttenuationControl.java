package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * DistanceAttenuationControl is an interface for controlling how the sound
 * from a sound source is attenuated with its distance from the Spectator.
 * Distances are specified in millimeters and the rolloff factor in thousandths.
 */
public interface DistanceAttenuationControl extends Control {

	int getMinDistance();

	int getMaxDistance();

	boolean getMuteAfterMax();

	int getRolloffFactor();

	void setParameters(int minDistance, int maxDistance, boolean muteAfterMax, int rolloffFactor);
}
