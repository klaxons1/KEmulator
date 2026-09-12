package javax.microedition.amms.control.imageeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * ImageTonalityControl is an interface for manipulating the tonality
 * (brightness, contrast and gamma) of an image.
 */
public interface ImageTonalityControl extends EffectControl {
	int AUTO = -1000;
	int NEXT = -1001;
	int PREVIOUS = -1002;

	int setBrightness(int brightness);

	int getBrightness();

	int getBrightnessLevels();

	int setContrast(int contrast);

	int getContrast();

	int getContrastLevels();

	int setGamma(int gamma);

	int getGamma();

	int getGammaLevels();
}
