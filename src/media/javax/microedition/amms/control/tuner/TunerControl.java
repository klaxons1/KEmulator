package javax.microedition.amms.control.tuner;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * TunerControl controls the features of a tuner (AM/FM radio).
 */
public interface TunerControl extends Control {
	int MONO = 1;
	int STEREO = 2;
	int AUTO = 3;
	String MODULATION_FM = "fm";
	String MODULATION_AM = "am";

	int getMinFreq(String modulation);

	int getMaxFreq(String modulation);

	int setFrequency(int freq, String modulation);

	int getFrequency();

	int seek(int step, String modulation, boolean squelch) throws MediaException;

	boolean getSquelch();

	void setSquelch(boolean state) throws MediaException;

	String getModulation();

	int getSignalStrength() throws MediaException;

	int getStereoMode();

	void setStereoMode(int stereoMode);

	int getNumberOfPresets();

	void usePreset(int preset);

	void setPreset(int preset);

	void setPreset(int preset, int freq, String modulation, int stereoMode);

	int getPresetFrequency(int preset);

	String getPresetModulation(int preset);

	int getPresetStereoMode(int preset) throws MediaException;

	String getPresetName(int preset);

	void setPresetName(int preset, String name);
}
