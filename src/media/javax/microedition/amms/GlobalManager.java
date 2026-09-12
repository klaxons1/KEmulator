package javax.microedition.amms;

import emulator.media.audio3d.SoundSource3DImpl;

import javax.microedition.amms.control.audio3d.CommitControl;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * The GlobalManager handles the creation of EffectModules, SoundSource3Ds
 * and MediaProcessors. Furthermore, a Spectator can be got from the GlobalManager.
 */
public class GlobalManager {

	private static Spectator spectator;

	public static EffectModule createEffectModule() throws MediaException {
		throw new MediaException("Unsupported");
	}

	public static MediaProcessor createMediaProcessor(String inputType) throws MediaException {
		throw new MediaException("Unsupported");
	}

	public static SoundSource3D createSoundSource3D() throws MediaException {
		return new SoundSource3DImpl();
	}

	public static Control getControl(String controlType) {
		if (controlType == null) {
			throw new IllegalArgumentException();
		}
		if (controlType.contains("CommitControl")) {
			return new CommitControl() {
				public void commit() {
				}

				public boolean isDeferred() {
					return false;
				}

				public void setDeferred(boolean deferred) {
				}
			};
		}
		return null;
	}

	public static Control[] getControls() {
		return new Control[0];
	}

	public static Spectator getSpectator() throws MediaException {
		if (spectator == null) {
			spectator = new SpectatorImpl();
		}
		return spectator;
	}

	public static String[] getSupportedMediaProcessorInputTypes() {
		return new String[0];
	}

	public static String[] getSupportedSoundSource3DPlayerTypes() {
		return new String[]{"audio/wav", "audio/x-wav", "audio/amr", "audio/mpeg"};
	}
}
