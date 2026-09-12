package javax.microedition.amms;

import javax.microedition.amms.control.audio3d.CommitControl;
import javax.microedition.amms.control.audio3d.DopplerControl;
import javax.microedition.amms.control.audio3d.LocationControl;
import javax.microedition.amms.control.audio3d.OrientationControl;
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
		throw new MediaException("Unsupported");
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
			spectator = new Spectator(new ControlProvider());
		}
		return spectator;
	}

	public static String[] getSupportedMediaProcessorInputTypes() {
		return new String[0];
	}

	public static String[] getSupportedSoundSource3DPlayerTypes() {
		return new String[0];
	}

	// No-op stub controls for the virtual acoustical space listener
	private static class ControlProvider implements Controllable {
		public Control getControl(String controlType) {
			if (controlType == null) {
				return null;
			}
			if (controlType.contains("LocationControl")) {
				return new LocationControl() {
					public int[] getCartesian() {
						return new int[3];
					}

					public void setCartesian(int x, int y, int z) {
					}

					public void setSpherical(int magnitude, int azimuth, int polar) {
					}
				};
			}
			if (controlType.contains("OrientationControl")) {
				return new OrientationControl() {
					public int[] getOrientationVectors() {
						// Default orientation: front (0, 0, -1000), up (0, 1000, 0)
						return new int[]{0, 0, -1000, 0, 1000, 0};
					}

					public void setOrientation(int[] frontVector, int[] aboveVector) throws IllegalArgumentException {
					}

					public void setOrientation(int heading, int pitch, int roll) {
					}
				};
			}
			if (controlType.contains("DopplerControl")) {
				return new DopplerControl() {
					public int[] getVelocityCartesian() {
						return new int[3];
					}

					public void setVelocityCartesian(int x, int y, int z) {
					}

					public void setVelocitySpherical(int magnitude, int azimuth, int polar) {
					}

					public boolean isEnabled() {
						return false;
					}

					public void setEnabled(boolean enabled) {
					}
				};
			}
			return null;
		}

		public Control[] getControls() {
			return new Control[0];
		}
	}
}
