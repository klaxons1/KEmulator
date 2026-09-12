package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * ObstructionControl provides a mechanism to control the overall level of
 * an audio signal flowing directly from a sound source to the Spectator,
 * and to attenuate the high frequency components of the signal.
 * Levels are specified in millibels.
 */
public interface ObstructionControl extends Control {

	int getLevel();

	void setLevel(int level);

	int getHFLevel();

	void setHFLevel(int HFLevel);
}
