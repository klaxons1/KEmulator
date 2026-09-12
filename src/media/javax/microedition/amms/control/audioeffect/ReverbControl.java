package javax.microedition.amms.control.audioeffect;

import javax.microedition.amms.control.EffectControl;
import javax.microedition.media.MediaException;

/**
 * ReverbControl is an interface for manipulating the settings of an audio
 * effect called reverb. A ReverbControl can only be fetched from the
 * GlobalManager and/or MediaProcessor (if ReverbControl is supported at all).
 */
public interface ReverbControl extends EffectControl {

	int setReverbLevel(int level) throws IllegalArgumentException;

	int getReverbLevel();

	void setReverbTime(int time) throws IllegalArgumentException, MediaException;

	int getReverbTime() throws MediaException;
}
