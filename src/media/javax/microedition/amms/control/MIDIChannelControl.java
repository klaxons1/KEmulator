package javax.microedition.amms.control;

import javax.microedition.media.Control;

/**
 * MIDIChannelControl is a Control that gives access to MIDI-channel-specific
 * Controls. Essentially, it provides the same functionality as Controllable,
 * but per channel, not per Player.
 */
public interface MIDIChannelControl extends Control {

	Control getChannelControl(String controlType, int channel);

	Control[] getChannelControls(int channel);
}
