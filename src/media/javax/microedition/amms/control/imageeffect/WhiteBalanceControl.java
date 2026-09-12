package javax.microedition.amms.control.imageeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * WhiteBalanceControl is an interface for manipulating the white balance
 * of an image.
 */
public interface WhiteBalanceControl extends EffectControl {
	int AUTO = -1000;
	int NEXT = -1001;
	int PREVIOUS = -1002;
	int UNKNOWN = -1004;

	int setColorTemp(int temp);

	int getColorTemp();

	int getMinColorTemp();

	int getMaxColorTemp();

	int getNumberOfSteps();
}
