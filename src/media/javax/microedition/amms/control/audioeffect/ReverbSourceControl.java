package javax.microedition.amms.control.audioeffect;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * ReverbSourceControl is an interface for manipulating the feeding from an
 * object to the audio effect called reverb. A ReverbSourceControl can only
 * be fetched from an EffectModule or a SoundSource3D.
 */
public interface ReverbSourceControl extends Control {
	int DISCONNECT = Integer.MAX_VALUE;

	void setRoomLevel(int level) throws MediaException;

	int getRoomLevel();
}
