package emulator.media.audio3d;

import javax.microedition.amms.SoundSource3D;
import javax.microedition.amms.control.audio3d.CommitControl;
import javax.microedition.amms.control.audio3d.DistanceAttenuationControl;
import javax.microedition.amms.control.audio3d.DopplerControl;
import javax.microedition.amms.control.audio3d.DirectivityControl;
import javax.microedition.amms.control.audio3d.LocationControl;
import javax.microedition.amms.control.audio3d.MacroscopicControl;
import javax.microedition.amms.control.audio3d.ObstructionControl;
import javax.microedition.amms.control.audio3d.OrientationControl;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

/**
 * JSR-234 1.1 {@link SoundSource3D} backed by OpenAL (see {@link Audio3DContext}).
 * <p>
 * The spatialization itself (distance gain, directivity, obstruction, Doppler)
 * is computed per pump cycle in {@link Source3DChannel} using the exact
 * formulas from the spec; the controls below only hold the parameters.
 */
public class SoundSource3DImpl implements SoundSource3D {

	/**
	 * All spatial parameters, shared by the controls and the pump.
	 * Distances in mm, velocities in mm/s, angles in degrees, levels in mB.
	 */
	static final class Params {
		volatile int x, y, z;
		volatile double heading, pitch, roll;
		volatile int minDistance = 1000;
		volatile int maxDistance = Integer.MAX_VALUE;
		volatile boolean muteAfterMax = true;
		volatile int rolloffFactor = 1000;
		volatile int minAngle = 360;
		volatile int maxAngle = 360;
		volatile int rearLevel = 0;
		volatile boolean dopplerEnabled = false;
		volatile int dvx, dvy, dvz;
		volatile int obsLevel = 0;
		volatile int obsHFLevel = 0;
		volatile int sizeX, sizeY, sizeZ;
	}

	final Params p = new Params();
	final CommitControlImpl commitControl = new CommitControlImpl(this);
	final LocationControlImpl locationControl = new LocationControlImpl(this);
	final DirectivityControlImpl directivityControl = new DirectivityControlImpl(this);
	final MacroscopicControlImpl macroscopicControl = new MacroscopicControlImpl(this);
	final DistanceAttenuationControlImpl distanceAttenuationControl = new DistanceAttenuationControlImpl(this);
	final DopplerControlImpl dopplerControl = new DopplerControlImpl(this, false);
	final ObstructionControlImpl obstructionControl = new ObstructionControlImpl(this);

	final Vector<Source3DChannel> channels = new Vector<>();

	/**
	 * @throws MediaException if the OpenAL context cannot be started.
	 */
	public SoundSource3DImpl() throws MediaException {
		Audio3DContext.instance().ensureStarted();
		if (!Audio3DContext.instance().isReady()) {
			throw new MediaException(Audio3DContext.instance().status());
		}
	}

	// ---------- Module ----------

	public void addPlayer(Player player) throws MediaException {
		if (player == null) {
			throw new IllegalArgumentException("player is null");
		}
		if (!(player instanceof javax.microedition.media.PlayerImpl)) {
			throw new MediaException("Player type is not supported for 3D audio");
		}
		javax.microedition.media.PlayerImpl pi = (javax.microedition.media.PlayerImpl) player;
		if (pi.audio3d != null) {
			throw new MediaException("Player is already attached to a SoundSource3D");
		}
		if (pi.getState() != Player.REALIZED) {
			throw new MediaException("Player is not in REALIZED state");
		}
		synchronized (channels) {
			for (Source3DChannel ch : channels) {
				int st = ch.playerImpl.getState();
				if (st != Player.REALIZED) {
					throw new MediaException("A connected player is in UNREALIZED, PREFETCHED or STARTED state");
				}
			}
			if (!Audio3DContext.instance().isReady()) {
				throw new MediaException(Audio3DContext.instance().status());
			}
			Source3DChannel ch = new Source3DChannel(this, pi);
			ch.create();
			channels.add(ch);
			pi.audio3d = ch;
		}
	}

	/**
	 * Spec: players may not be added or removed while any connected player is
	 * in the UNREALIZED, PREFETCHED or STARTED state.
	 */
	private void checkModuleStates() {
		for (Source3DChannel ch : channels) {
			int st = ch.playerImpl.getState();
			if (st != Player.REALIZED) {
				throw new IllegalStateException("A connected player is in UNREALIZED, PREFETCHED or STARTED state");
			}
		}
	}

	public void removePlayer(Player player) {
		if (player == null) {
			return;
		}
		synchronized (channels) {
			checkModuleStates();
			Source3DChannel ch = null;
			for (Source3DChannel c : channels) {
				if (c.playerImpl == player) {
					ch = c;
					break;
				}
			}
			if (ch == null) {
				return;
			}
			channels.remove(ch);
			ch.detach();
		}
		if (player instanceof javax.microedition.media.PlayerImpl) {
			((javax.microedition.media.PlayerImpl) player).audio3d = null;
		}
	}

	public void addMIDIChannel(Player player, int channel) throws MediaException {
		throw new MediaException("MIDI channels are not supported");
	}

	public void removeMIDIChannel(Player player, int channel) {
	}

	// ---------- Controllable ----------

	public Control getControl(String controlType) {
		if (controlType == null) {
			throw new IllegalArgumentException("controlType is null");
		}
		String t = controlType;
		if (t.contains("LocationControl")) {
			return locationControl;
		}
		if (t.contains("DistanceAttenuationControl")) {
			return distanceAttenuationControl;
		}
		if (t.contains("DirectivityControl")) {
			return directivityControl;
		}
		if (t.contains("MacroscopicControl")) {
			return macroscopicControl;
		}
		if (t.contains("DopplerControl")) {
			return dopplerControl;
		}
		if (t.contains("ObstructionControl")) {
			return obstructionControl;
		}
		if (t.contains("CommitControl")) {
			return commitControl;
		}
		if (t.contains("OrientationControl")) {
			return directivityControl;
		}
		return null;
	}

	public Control[] getControls() {
		return new Control[]{locationControl, distanceAttenuationControl, directivityControl,
				macroscopicControl, dopplerControl, obstructionControl, commitControl};
	}

	// ---------- controls ----------

	private void defer(Control control, Runnable apply) {
		commitControl.defer(control, apply);
	}

	class LocationControlImpl implements LocationControl {
		private final SoundSource3DImpl owner;

		LocationControlImpl(SoundSource3DImpl owner) {
			this.owner = owner;
		}

		public int[] getCartesian() {
			return new int[]{owner.p.x, owner.p.y, owner.p.z};
		}

		public void setCartesian(int x, int y, int z) {
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.x = x;
					owner.p.y = y;
					owner.p.z = z;
				}
			});
		}

		public void setSpherical(int azimuth, int elevation, int radius) {
			if (radius < 0) {
				throw new IllegalArgumentException("radius must not be negative");
			}
			final double[] c = Vec3.sphericalToCartesian(azimuth, elevation, radius);
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.x = (int) Math.round(c[0]);
					owner.p.y = (int) Math.round(c[1]);
					owner.p.z = (int) Math.round(c[2]);
				}
			});
		}
	}

	class OrientationControlImpl implements OrientationControl {
		// package-private: inherited by DirectivityControlImpl / MacroscopicControlImpl
		final SoundSource3DImpl owner;

		OrientationControlImpl(SoundSource3DImpl owner) {
			this.owner = owner;
		}

		public int[] getOrientationVectors() {
			double[] f = new double[3];
			double[] u = new double[3];
			Vec3.orientationFromAngles(owner.p.heading, owner.p.pitch, owner.p.roll, f, u);
			return new int[]{
					(int) Math.round(f[0] * 1000), (int) Math.round(f[1] * 1000), (int) Math.round(f[2] * 1000),
					(int) Math.round(u[0] * 1000), (int) Math.round(u[1] * 1000), (int) Math.round(u[2] * 1000)};
		}

		public void setOrientation(int[] frontVector, int[] aboveVector) throws IllegalArgumentException {
			if (frontVector == null || aboveVector == null
					|| frontVector.length != 3 || aboveVector.length != 3) {
				throw new IllegalArgumentException("orientation vectors must have 3 elements");
			}
			double fx = frontVector[0], fy = frontVector[1], fz = frontVector[2];
			double ax = aboveVector[0], ay = aboveVector[1], az = aboveVector[2];
			if (Vec3.length(fx, fy, fz) < 1e-9 || Vec3.length(ax, ay, az) < 1e-9) {
				throw new IllegalArgumentException("orientation vector is zero");
			}
			double rx = fy * az - fz * ay, ry = fz * ax - fx * az, rz = fx * ay - fy * ax;
			if (Vec3.length(rx, ry, rz) < 1e-9 * Vec3.length(fx, fy, fz) * Vec3.length(ax, ay, az)) {
				throw new IllegalArgumentException("orientation vectors are parallel");
			}
			double[] f = {fx, fy, fz};
			double[] a = {ax, ay, az};
			double[] r = new double[3];
			double[] u = new double[3];
			Vec3.deriveRightUp(f, a, r, u);
			final double[] angles = new double[3];
			Vec3.orientationToAngles(f, u, angles);
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.heading = angles[0];
					owner.p.pitch = angles[1];
					owner.p.roll = angles[2];
				}
			});
		}

		public void setOrientation(int heading, int pitch, int roll) {
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.heading = heading;
					owner.p.pitch = pitch;
					owner.p.roll = roll;
				}
			});
		}
	}

	class DirectivityControlImpl extends OrientationControlImpl implements DirectivityControl {
		DirectivityControlImpl(SoundSource3DImpl owner) {
			super(owner);
		}

		public int[] getParameters() {
			return new int[]{owner.p.minAngle, owner.p.maxAngle, owner.p.rearLevel};
		}

		public void setParameters(int minAngle, int maxAngle, int rearLevel) {
			if (minAngle < 0 || minAngle > 360 || maxAngle < 0 || maxAngle > 360 || rearLevel > 0) {
				throw new IllegalArgumentException("invalid directivity parameters");
			}
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.minAngle = minAngle;
					owner.p.maxAngle = maxAngle;
					owner.p.rearLevel = rearLevel;
				}
			});
		}
	}

	class MacroscopicControlImpl extends OrientationControlImpl implements MacroscopicControl {
		MacroscopicControlImpl(SoundSource3DImpl owner) {
			super(owner);
		}

		public int[] getSize() {
			return new int[]{owner.p.sizeX, owner.p.sizeY, owner.p.sizeZ};
		}

		public void setSize(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0) {
				throw new IllegalArgumentException("size must not be negative");
			}
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.sizeX = x;
					owner.p.sizeY = y;
					owner.p.sizeZ = z;
				}
			});
		}
	}

	class DistanceAttenuationControlImpl implements DistanceAttenuationControl {
		private final SoundSource3DImpl owner;

		DistanceAttenuationControlImpl(SoundSource3DImpl owner) {
			this.owner = owner;
		}

		public int getMinDistance() {
			return owner.p.minDistance;
		}

		public int getMaxDistance() {
			return owner.p.maxDistance;
		}

		public boolean getMuteAfterMax() {
			return owner.p.muteAfterMax;
		}

		public int getRolloffFactor() {
			return owner.p.rolloffFactor;
		}

		public void setParameters(int minDistance, int maxDistance, boolean muteAfterMax, int rolloffFactor) {
			if (minDistance <= 0 || maxDistance < minDistance || rolloffFactor < 0) {
				throw new IllegalArgumentException("invalid distance attenuation parameters");
			}
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.minDistance = minDistance;
					owner.p.maxDistance = maxDistance;
					owner.p.muteAfterMax = muteAfterMax;
					owner.p.rolloffFactor = rolloffFactor;
				}
			});
		}
	}

	class DopplerControlImpl implements DopplerControl {
		private final SoundSource3DImpl owner;
		private final boolean isListener;
		private volatile boolean enabled;

		DopplerControlImpl(SoundSource3DImpl owner, boolean isListener) {
			this.owner = owner;
			this.isListener = isListener;
			this.enabled = isListener; // spectator doppler is on by default and cannot be disabled
		}

		public int[] getVelocityCartesian() {
			return new int[]{owner.p.dvx, owner.p.dvy, owner.p.dvz};
		}

		public void setVelocityCartesian(int x, int y, int z) {
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.dvx = x;
					owner.p.dvy = y;
					owner.p.dvz = z;
				}
			});
		}

		public void setVelocitySpherical(int azimuth, int elevation, int radius) {
			if (radius < 0) {
				throw new IllegalArgumentException("radius must not be negative");
			}
			final double[] c = Vec3.sphericalToCartesian(azimuth, elevation, radius);
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.dvx = (int) Math.round(c[0]);
					owner.p.dvy = (int) Math.round(c[1]);
					owner.p.dvz = (int) Math.round(c[2]);
				}
			});
		}

		public boolean isEnabled() {
			return isListener || enabled;
		}

		public void setEnabled(boolean dopplerEnabled) {
			if (!isListener) {
				enabled = dopplerEnabled;
			}
		}
	}

	class ObstructionControlImpl implements ObstructionControl {
		private final SoundSource3DImpl owner;

		ObstructionControlImpl(SoundSource3DImpl owner) {
			this.owner = owner;
		}

		public int getLevel() {
			return owner.p.obsLevel;
		}

		public void setLevel(int level) {
			if (level > 0) {
				throw new IllegalArgumentException("level must not be positive");
			}
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.obsLevel = level;
				}
			});
		}

		public int getHFLevel() {
			return owner.p.obsHFLevel;
		}

		public void setHFLevel(int HFLevel) {
			if (HFLevel > 0) {
				throw new IllegalArgumentException("HFLevel must not be positive");
			}
			SoundSource3DImpl.this.defer(this, new Runnable() {
				@Override
				public void run() {
					owner.p.obsHFLevel = HFLevel;
				}
			});
		}
	}

	class CommitControlImpl implements CommitControl {
		private final SoundSource3DImpl owner;
		private volatile boolean deferred;
		private final Map<Control, Runnable> pending = new LinkedHashMap<Control, Runnable>();

		CommitControlImpl(SoundSource3DImpl owner) {
			this.owner = owner;
		}

		public void commit() {
			synchronized (pending) {
				for (Runnable r : pending.values()) {
					try {
						r.run();
					} catch (Throwable ignored) {
					}
				}
				pending.clear();
			}
		}

		public boolean isDeferred() {
			return deferred;
		}

		public void setDeferred(boolean deferred) {
			this.deferred = deferred;
			if (!deferred) {
				commit();
			}
		}

		void defer(Control control, Runnable apply) {
			if (!deferred) {
				apply.run();
				return;
			}
			synchronized (pending) {
				pending.put(control, apply);
			}
		}
	}
}
