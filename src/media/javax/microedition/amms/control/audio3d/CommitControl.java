package javax.microedition.amms.control.audio3d;

import javax.microedition.media.Control;

/**
 * CommitControl is used to control the time at which the current changes
 * to a control should take effect.
 */
public interface CommitControl extends Control {

	void commit();

	boolean isDeferred();

	void setDeferred(boolean deferred);
}
