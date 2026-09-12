package javax.microedition.amms.control.audio3d;

/**
 * MacroscopicControl is an interface for manipulating the macroscopic
 * behavior of a sound source when using 3D audio. By default, sound
 * sources act as point sources (having zero size); this control allows
 * the dimensions of a sound source to be specified in millimeters.
 */
public interface MacroscopicControl extends OrientationControl {

	int[] getSize();

	void setSize(int x, int y, int z);
}
