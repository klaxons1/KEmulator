package javax.microedition.amms.control.imageeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * ImageTransformControl is an interface for manipulating the size and
 * position of the image (cropping, scaling, rotation, etc.).
 */
public interface ImageTransformControl extends EffectControl {

	int getSourceWidth();

	int getSourceHeight();

	void setSourceRect(int x, int y, int w, int h);

	void setTargetSize(int w, int h, int scale);
}
