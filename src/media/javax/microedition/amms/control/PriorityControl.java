package javax.microedition.amms.control;

import javax.microedition.media.Control;

/**
 * PriorityControl is an interface for manipulating the priority of a
 * Player among other Players. The priority level ranges from 0 (the
 * lowest priority) to 100 (the highest priority), 50 being the default.
 */
public interface PriorityControl extends Control {

	void setPriority(int priority);

	int getPriority();
}
