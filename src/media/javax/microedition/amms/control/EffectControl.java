package javax.microedition.amms.control;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * EffectControl is an interface for controlling an abstract filter
 * with various preset settings.
 */
public interface EffectControl extends Control {
	int SCOPE_LIVE_ONLY = 1;
	int SCOPE_RECORD_ONLY = 2;
	int SCOPE_LIVE_AND_RECORD = 3;

	void setEnabled(boolean enable);

	boolean isEnabled();

	void setScope(int scope) throws MediaException;

	int getScope();

	void setEnforced(boolean enforced);

	boolean isEnforced();

	void setPreset(String preset);

	String getPreset();

	String[] getPresetNames();
}
