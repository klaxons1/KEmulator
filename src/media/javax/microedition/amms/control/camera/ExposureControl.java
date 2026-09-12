package javax.microedition.amms.control.camera;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * ExposureControl controls the exposure settings of the camera device.
 * Exposure is based on three components: aperture, shutter speed
 * (exposure time) and sensitivity.
 */
public interface ExposureControl extends Control {

	int[] getSupportedFStops();

	int getFStop();

	void setFStop(int aperture) throws MediaException;

	int getMinExposureTime();

	int getMaxExposureTime();

	int getExposureTime();

	int setExposureTime(int time) throws MediaException;

	int[] getSupportedISOs();

	int getISO();

	void setISO(int iso) throws MediaException;

	int[] getSupportedExposureCompensations();

	int getExposureCompensation();

	void setExposureCompensation(int ec) throws MediaException;

	int getExposureValue();

	String[] getSupportedLightMeterings();

	void setLightMetering(String metering);

	String getLightMetering();
}
