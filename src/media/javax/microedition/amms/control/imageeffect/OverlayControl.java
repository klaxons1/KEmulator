package javax.microedition.amms.control.imageeffect;

import javax.microedition.amms.control.EffectControl;

/**
 * OverlayControl is an interface for inserting overlays (such as date
 * stamps or logos) onto the captured image.
 */
public interface OverlayControl extends EffectControl {

	int insertImage(Object image, int x, int y, int z) throws IllegalArgumentException;

	int insertImage(Object image, int x, int y, int z, int id) throws IllegalArgumentException;

	void removeImage(Object image);

	Object getImage(int id);

	int numberOfImages();

	void clear();
}
