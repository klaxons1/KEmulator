package javax.microedition.amms.control;

/**
 * ContainerFormatControl controls the setting of the container formats.
 * It specifies the file format of an audio-video container format.
 */
public interface ContainerFormatControl extends FormatControl {

	void setFormat(String format);
}
