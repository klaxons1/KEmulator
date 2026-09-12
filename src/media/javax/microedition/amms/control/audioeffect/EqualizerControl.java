package javax.microedition.amms.control.audioeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * EqualizerControl is an audio EffectControl for manipulating the
 * equalization settings of a Player(s).
 */
public interface EqualizerControl extends EffectControl {
	int UNDEFINED = -1004;

	int getNumberOfBands();

	int getCenterFreq(int band) throws IllegalArgumentException;

	int getBand(int frequency);

	void setBandLevel(int level, int band) throws IllegalArgumentException;

	int getBandLevel(int band) throws IllegalArgumentException;

	int getMinBandLevel();

	int getMaxBandLevel();

	int setBass(int level) throws IllegalArgumentException;

	int getBass();

	int setTreble(int level) throws IllegalArgumentException;

	int getTreble();
}
