package javax.microedition.amms;

import javax.microedition.media.Controllable;
import javax.microedition.media.MediaException;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * MediaProcessor is an interface designed to post-process different media types.
 */
public interface MediaProcessor extends Controllable {
	int UNREALIZED = 100;
	int REALIZED = 200;
	int STOPPED = 300;
	int STARTED = 400;
	int UNKNOWN = -1;

	void setInput(InputStream input, int length) throws MediaException;

	void setInput(Object image) throws MediaException;

	void setOutput(OutputStream output);

	void start() throws MediaException;

	void stop() throws MediaException;

	void abort();

	void complete() throws MediaException;

	int getState();

	int getProgress();

	void addMediaProcessorListener(MediaProcessorListener mediaProcessorListener);

	void removeMediaProcessorListener(MediaProcessorListener mediaProcessorListener);
}
