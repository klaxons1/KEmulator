package javax.microedition.amms.control;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

/**
 * FormatControl controls the format used for storing media.
 * It is a super interface for ContainerFormatControl, ImageFormatControl,
 * AudioFormatControl and VideoFormatControl.
 */
public interface FormatControl extends Control {
	int METADATA_NOT_SUPPORTED = 0;
	int METADATA_SUPPORTED_FIXED_KEYS = 1;
	int METADATA_SUPPORTED_FREE_KEYS = 2;

	String PARAM_BITRATE = "bitrate";
	String PARAM_BITRATE_TYPE = "bitrate type";
	String PARAM_SAMPLERATE = "sample rate";
	String PARAM_FRAMERATE = "frame rate";
	String PARAM_QUALITY = "quality";
	String PARAM_VERSION_TYPE = "version type";

	String[] getSupportedFormats();

	String getFormat();

	void setFormat(String format);

	String[] getSupportedIntParameters();

	String[] getSupportedStrParameters();

	int[] getSupportedIntParameterRange(String parameter);

	String[] getSupportedStrParameterValues(String parameter);

	int setParameter(String parameter, int value);

	void setParameter(String parameter, String value);

	int getIntParameterValue(String parameter);

	String getStrParameterValue(String parameter);

	int getEstimatedBitRate() throws MediaException;

	void setMetadata(String key, String value) throws MediaException;

	String[] getSupportedMetadataKeys();

	int getMetadataSupportMode();

	void setMetadataOverride(boolean override);

	boolean getMetadataOverride();
}
