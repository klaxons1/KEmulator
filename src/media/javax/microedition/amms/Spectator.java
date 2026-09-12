package javax.microedition.amms;

import javax.microedition.media.Control;
import javax.microedition.media.Controllable;

/**
 * Spectator represents the listener in the virtual acoustical space.
 * The GlobalManager.getSpectator() method is used to retrieve a Spectator.
 */
public class Spectator implements Controllable {
	private final Controllable specImpl;

	Spectator(Controllable impl) {
		this.specImpl = impl;
	}

	public Control getControl(String controlType) {
		return this.specImpl.getControl(controlType);
	}

	public Control[] getControls() {
		return this.specImpl.getControls();
	}
}
