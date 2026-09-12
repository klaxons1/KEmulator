package emulator.media.audio3d;

/**
 * Small vector helpers implementing the exact conventions of JSR-234:
 * right-handed coordinates, azimuth measured from the negative Z axis toward
 * the X axis, elevation measured from the X-Z plane toward the Y axis, and
 * the heading/pitch/roll to Front/Up transformation as specified in the
 * OrientationControl javadoc.
 */
public final class Vec3 {

	private Vec3() {
	}

	/**
	 * Spherical (azimuth, elevation, radius) to Cartesian, exactly as
	 * LocationControl.setSpherical specifies.
	 */
	public static double[] sphericalToCartesian(int azimuth, int elevation, int radius) {
		double az = Math.toRadians(azimuth);
		double el = Math.toRadians(elevation);
		double r = radius;
		double ce = Math.cos(el);
		return new double[]{r * ce * Math.sin(az), r * Math.sin(el), -r * ce * Math.cos(az)};
	}

	public static double length(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z);
	}

	/**
	 * JSR-234 OrientationControl "implementation note" transformation.
	 *
	 * @param heading rotation around the Y axis in degrees
	 * @param pitch   rotation around the X axis in degrees
	 * @param roll    rotation around the Z axis in degrees
	 * @param front   output unit front vector (3 elements)
	 * @param up      output unit up vector (3 elements)
	 */
	public static void orientationFromAngles(double heading, double pitch, double roll, double[] front, double[] up) {
		double h = Math.toRadians(heading);
		double p = Math.toRadians(pitch);
		double r = Math.toRadians(roll);
		double sh = Math.sin(h), ch = Math.cos(h);
		double sp = Math.sin(p), cp = Math.cos(p);
		double sr = Math.sin(r), cr = Math.cos(r);
		front[0] = -sh * cp;
		front[1] = sp;
		front[2] = -ch * cp;
		up[0] = -sr * ch + cr * sp * sh;
		up[1] = cp * cr;
		up[2] = sr * sh + cr * ch * sp;
	}

	/**
	 * Inverse of {@link #orientationFromAngles(double, double, double, double[], double[])}:
	 * derives (heading, pitch, roll) in degrees from a unit front vector and the
	 * unit up vector derived from the front + above vectors.
	 */
	public static void orientationToAngles(double[] front, double[] up, double[] out) {
		double fL = length(front[0], front[1], front[2]);
		if (fL < 1e-9) {
			fL = 1e-9;
		}
		double fx = front[0] / fL, fy = front[1] / fL, fz = front[2] / fL;
		double h = Math.toDegrees(Math.atan2(-fx, -fz));
		double p = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, fy))));
		double hd = Math.toRadians(h), pd = Math.toRadians(p);
		// up = cos(r) * B1 + sin(r) * B2 with orthonormal B1, B2 (per the spec formula)
		double b1x = Math.sin(pd) * Math.sin(hd), b1y = Math.cos(pd), b1z = Math.cos(hd) * Math.sin(pd);
		double b2x = -Math.cos(hd), b2y = 0, b2z = Math.sin(hd);
		double r = Math.toDegrees(Math.atan2(up[0] * b2x + up[1] * b2y + up[2] * b2z,
				up[0] * b1x + up[1] * b1y + up[2] * b1z));
		out[0] = h;
		out[1] = p;
		out[2] = r;
	}

	/**
	 * Right = cross(front, above); Up = cross(right, front), normalized, as
	 * specified by OrientationControl.setOrientation(int[], int[]).
	 */
	public static void deriveRightUp(double[] front, double[] above, double[] right, double[] up) {
		right[0] = front[1] * above[2] - front[2] * above[1];
		right[1] = front[2] * above[0] - front[0] * above[2];
		right[2] = front[0] * above[1] - front[1] * above[0];
		up[0] = right[1] * front[2] - right[2] * front[1];
		up[1] = right[2] * front[0] - right[0] * front[2];
		up[2] = right[0] * front[1] - right[1] * front[0];
		double rL = length(right[0], right[1], right[2]);
		if (rL > 1e-9) {
			right[0] /= rL;
			right[1] /= rL;
			right[2] /= rL;
		}
		double uL = length(up[0], up[1], up[2]);
		if (uL > 1e-9) {
			up[0] /= uL;
			up[1] /= uL;
			up[2] /= uL;
		}
	}

	/**
	 * Angle in degrees between two direction vectors (0..180).
	 */
	public static double angleBetween(double[] a, double[] b) {
		double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
		return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, d))));
	}
}
