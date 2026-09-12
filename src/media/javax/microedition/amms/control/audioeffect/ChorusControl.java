package javax.microedition.amms.control.audioeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * ChorusControl is an interface for manipulating the settings of an audio
 * effect called chorus and its special case flanger.
 */
public interface ChorusControl extends EffectControl {

	int setWetLevel(int level);

	int getWetLevel();

	void setModulationRate(int rate);

	int getModulationRate();

	int getMinModulationRate();

	int getMaxModulationRate();

	void setModulationDepth(int percentage);

	int getModulationDepth();

	int getMaxModulationDepth();

	void setAverageDelay(int delay);

	int getAverageDelay();

	int getMaxAverageDelay();
}
