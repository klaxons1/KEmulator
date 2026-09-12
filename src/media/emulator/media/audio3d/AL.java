package emulator.media.audio3d;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * Thin JNA binding over the OpenAL 1.1 API (see also {@link Audio3DContext}).
 * <p>
 * The native library is loaded per platform:
 * <ul>
 * <li>Windows: <code>openal32</code> (OpenAL Soft <code>openal32.dll</code> must be on the library path)</li>
 * <li>Linux: <code>openal</code> (<code>libopenal.so</code>)</li>
 * <li>macOS: the system OpenAL framework</li>
 * </ul>
 * All enum constants below are the canonical OpenAL 1.0/1.1 values and match
 * <code>org.lwjgl.openal.AL10</code>/<code>AL11</code> one-to-one, so if the
 * official LWJGL OpenAL bindings are ever added to the classpath this facade
 * can be swapped out mechanically (same constants, same C function names).
 */
public interface AL extends Library {

	// ---------- errors ----------
	int AL_NO_ERROR = 0;
	int AL_INVALID_NAME = 0xA001;
	int AL_INVALID_ENUM = 0xA002;
	int AL_INVALID_VALUE = 0xA003;
	int AL_INVALID_OPERATION = 0xA004;
	int AL_OUT_OF_MEMORY = 0xA005;

	// ---------- generic ----------
	int AL_FALSE = 0;
	int AL_TRUE = 1;
	int AL_NONE = 0;

	// ---------- source parameters ----------
	int AL_SOURCE_ABSOLUTE = 0x201;
	int AL_SOURCE_RELATIVE = 0x202;

	int AL_CONE_INNER_ANGLE = 0x1001;
	int AL_CONE_OUTER_ANGLE = 0x1002;
	int AL_PITCH = 0x1003;
	int AL_POSITION = 0x1004;
	int AL_DIRECTION = 0x1005;
	int AL_VELOCITY = 0x1006;
	int AL_LOOPING = 0x1007;
	int AL_BUFFER = 0x1009;
	int AL_GAIN = 0x100A;
	int AL_MIN_GAIN = 0x100D;
	int AL_MAX_GAIN = 0x100E;
	int AL_ORIENTATION = 0x100F;
	int AL_SOURCE_STATE = 0x1010;
	int AL_INITIAL = 0x1011;
	int AL_PLAYING = 0x1012;
	int AL_PAUSED = 0x1013;
	int AL_STOPPED = 0x1014;
	int AL_BUFFERS_QUEUED = 0x1015;
	int AL_BUFFERS_PROCESSED = 0x1016;
	int AL_REFERENCE_DISTANCE = 0x1020;
	int AL_ROLLOFF_FACTOR = 0x1021;
	int AL_CONE_OUTER_GAIN = 0x1022;
	int AL_MAX_DISTANCE = 0x1023;
	int AL_SEC_OFFSET = 0x1024;
	int AL_SAMPLE_OFFSET = 0x1025;
	int AL_BYTE_OFFSET = 0x1026;
	int AL_SOURCE_TYPE = 0x1027;
	int AL_STATIC = 0x1028;
	int AL_STREAMING = 0x1029;
	int AL_UNDETERMINED = 0x1030;

	// ---------- buffer parameters ----------
	int AL_FORMAT_MONO8 = 0x1100;
	int AL_FORMAT_MONO16 = 0x1101;
	int AL_FORMAT_STEREO8 = 0x1102;
	int AL_FORMAT_STEREO16 = 0x1103;

	int AL_FREQUENCY = 0x2001;
	int AL_BITS = 0x2002;
	int AL_CHANNELS = 0x2003;
	int AL_SIZE = 0x2004;
	int AL_PROCESSED = 0x2012;

	// ---------- global parameters ----------
	int AL_VENDOR = 0xB001;
	int AL_VERSION = 0xB002;
	int AL_RENDERER = 0xB003;
	int AL_EXTENSIONS = 0xB004;

	int AL_DOPPLER_FACTOR = 0xC000;
	int AL_DOPPLER_VELOCITY = 0xC001;
	int AL_SPEED_OF_SOUND = 0xC003;

	// ---------- ALC ----------
	int ALC_FALSE = 0;
	int ALC_TRUE = 1;
	int ALC_NO_ERROR = 0;
	int ALC_INVALID_DEVICE = 0xA001;
	int ALC_INVALID_CONTEXT = 0xA002;
	int ALC_DEFAULT_DEVICE_SPECIFIER = 0x1004;
	int ALC_DEVICE_SPECIFIER = 0x1005;
	int ALC_EXTENSIONS = 0x1006;
	int ALC_MAJOR_VERSION = 0x1000;
	int ALC_MINOR_VERSION = 0x1001;

	// ---------- ALC functions ----------
	Pointer alcOpenDevice(String deviceSpecifier);

	boolean alcCloseDevice(Pointer device);

	Pointer alcCreateContext(Pointer device, int[] attributes);

	boolean alcMakeContextCurrent(Pointer context);

	void alcProcessContext(Pointer context);

	void alcSuspendContext(Pointer context);

	void alcDestroyContext(Pointer context);

	int alcGetError(Pointer device);

	Pointer alcGetString(Pointer device, int param);

	// ---------- AL functions ----------
	int alGetError();

	Pointer alGetString(int param);

	boolean alIsExtensionPresent(String extName);

	void alGenBuffers(int n, int[] buffers);

	void alDeleteBuffers(int n, int[] buffers);

	void alBufferData(int buffer, int format, Pointer data, int size, int frequency);

	void alGenSources(int n, int[] sources);

	void alDeleteSources(int n, int[] sources);

	void alSourcef(int source, int param, float value);

	void alSource3f(int source, int param, float v1, float v2, float v3);

	void alSourcefv(int source, int param, float[] value);

	void alSourcei(int source, int param, int value);

	float alGetSourcef(int source, int param);

	int alGetSourcei(int source, int param);

	void alSourcePlay(int source);

	void alSourcePause(int source);

	void alSourceStop(int source);

	void alSourceQueueBuffers(int source, int nb, int[] bufferNames);

	void alSourceUnqueueBuffers(int source, int nb, int[] bufferNames);

	void alListenerfv(int param, float[] value);

	void alListenerf(int param, float value);

	void alListener3f(int param, float v1, float v2, float v3);
}
