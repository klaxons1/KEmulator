package javax.microedition.amms.control;

import javax.microedition.media.Control;

/**
 * PanControl is an interface for manipulating the panning of a Player
 * in the stereo output mix. If the input is stereo, this controls the
 * balance between the channels.
 */
public interface PanControl extends Control {

	int setPan(int pan);

	int getPan();
}
