package javax.microedition.amms;

import javax.microedition.media.Controllable;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;

/**
 * Module is a logical group of Players and/or MIDI channels.
 * <p>
 * Adding or removing Players or MIDI channels is not possible if any of
 * the Players is in UNREALIZED, PREFETCHED or STARTED state.
 */
public interface Module extends Controllable {

	void addPlayer(Player player) throws MediaException;

	void removePlayer(Player player);

	void addMIDIChannel(Player player, int channel) throws MediaException;

	void removeMIDIChannel(Player player, int channel);
}
