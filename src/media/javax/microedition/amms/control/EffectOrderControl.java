package javax.microedition.amms.control;

import javax.microedition.media.Control;

/**
 * EffectOrderControl is an interface designed to specify the order of
 * effects represented by EffectControls.
 */
public interface EffectOrderControl extends Control {

	int setEffectOrder(EffectControl effect, int order);

	int getEffectOrder(EffectControl effect);

	EffectControl[] getEffectOrders();
}
