package javax.microedition.amms;

import emulator.media.audio3d.AL;
import emulator.media.audio3d.Audio3DContext;
import emulator.media.audio3d.Vec3;
import javax.microedition.amms.control.audio3d.DopplerControl;
import javax.microedition.amms.control.audio3d.LocationControl;
import javax.microedition.amms.control.audio3d.OrientationControl;
import javax.microedition.media.Control;

/**
 * JSR-234 1.1 {@link Spectator}: the listener in the virtual acoustical space.
 * <p>
 * The state lives in static fields; the {@link Audio3DContext} pump applies it
 * to the OpenAL listener (position and orientation) every cycle. Doppler
 * velocities are used by the sources to compute the pitch shift.
 */
public class SpectatorImpl extends Spectator {

	/** Location in mm. */
	public static volatile int x, y, z;
	/** Doppler velocity in mm/s (always active for the spectator). */
	public static volatile int vx, vy, vz;
	/** Orientation angles in degrees. */
	public static volatile double heading, pitch, roll;

	private final LocationControlImpl locationControl = new LocationControlImpl();
	private final OrientationControlImpl orientationControl = new OrientationControlImpl();
	private final DopplerControlImpl dopplerControl = new DopplerControlImpl();

	static {
		Audio3DContext.instance().addTick(new Runnable() {
			@Override
			public void run() {
				applyListener();
			}
		});
	}

	private static void applyListener() {
		Audio3DContext ctx = Audio3DContext.instance();
		if (!ctx.isReady()) {
			return;
		}
		AL al = ctx.al();
		if (al == null) {
			return;
		}
		double[] f = new double[3];
		double[] u = new double[3];
		Vec3.orientationFromAngles(heading, pitch, roll, f, u);
		al.alListenerfv(AL.AL_POSITION, new float[]{x / 1000f, y / 1000f, z / 1000f});
		al.alListenerfv(AL.AL_ORIENTATION,
				new float[]{(float) f[0], (float) f[1], (float) f[2], (float) u[0], (float) u[1], (float) u[2]});
	}

	@Override
	public Control getControl(String controlType) {
		if (controlType == null) {
			throw new IllegalArgumentException("controlType is null");
		}
		String t = controlType;
		if (t.contains("LocationControl")) {
			return locationControl;
		}
		if (t.contains("OrientationControl")) {
			return orientationControl;
		}
		if (t.contains("DopplerControl")) {
			return dopplerControl;
		}
		return null;
	}

	@Override
	public Control[] getControls() {
		return new Control[]{locationControl, orientationControl, dopplerControl};
	}

	// ---------- controls ----------

	private static class LocationControlImpl implements LocationControl {
		public int[] getCartesian() {
			return new int[]{x, y, z};
		}

		public void setCartesian(int x, int y, int z) {
			SpectatorImpl.x = x;
			SpectatorImpl.y = y;
			SpectatorImpl.z = z;
		}

		public void setSpherical(int azimuth, int elevation, int radius) {
			if (radius < 0) {
				throw new IllegalArgumentException("radius must not be negative");
			}
			double[] c = Vec3.sphericalToCartesian(azimuth, elevation, radius);
			SpectatorImpl.x = (int) Math.round(c[0]);
			SpectatorImpl.y = (int) Math.round(c[1]);
			SpectatorImpl.z = (int) Math.round(c[2]);
		}
	}

	private static class OrientationControlImpl implements OrientationControl {
		public int[] getOrientationVectors() {
			double[] f = new double[3];
			double[] u = new double[3];
			Vec3.orientationFromAngles(heading, pitch, roll, f, u);
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
			double[] angles = new double[3];
			Vec3.orientationToAngles(f, u, angles);
			SpectatorImpl.heading = angles[0];
			SpectatorImpl.pitch = angles[1];
			SpectatorImpl.roll = angles[2];
		}

		public void setOrientation(int heading, int pitch, int roll) {
			SpectatorImpl.heading = heading;
			SpectatorImpl.pitch = pitch;
			SpectatorImpl.roll = roll;
		}
	}

	private static class DopplerControlImpl implements DopplerControl {
		public int[] getVelocityCartesian() {
			return new int[]{vx, vy, vz};
		}

		public void setVelocityCartesian(int x, int y, int z) {
			SpectatorImpl.vx = x;
			SpectatorImpl.vy = y;
			SpectatorImpl.vz = z;
		}

		public void setVelocitySpherical(int azimuth, int elevation, int radius) {
			if (radius < 0) {
				throw new IllegalArgumentException("radius must not be negative");
			}
			double[] c = Vec3.sphericalToCartesian(azimuth, elevation, radius);
			SpectatorImpl.vx = (int) Math.round(c[0]);
			SpectatorImpl.vy = (int) Math.round(c[1]);
			SpectatorImpl.vz = (int) Math.round(c[2]);
		}

		public boolean isEnabled() {
			return true;
		}

		public void setEnabled(boolean enabled) {
			// the spectator doppler control cannot be disabled
		}
	}
}
