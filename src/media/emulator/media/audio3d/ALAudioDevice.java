package emulator.media.audio3d;

import emulator.javazoom.jl.decoder.Decoder;

/**
 * A javazoom {@link emulator.javazoom.jl.player.AudioDevice} that pushes the
 * decoded MP3 PCM into a {@link Source3DChannel.MpegFeeder} ring instead of a
 * Java Sound line. The actual OpenAL enqueueing is done by the pump thread.
 */
public class ALAudioDevice implements emulator.javazoom.jl.player.AudioDevice {

	private final Source3DChannel channel;
	private volatile Decoder decoder;
	private volatile boolean open;
	private boolean formatNotified;

	ALAudioDevice(Source3DChannel channel) {
		this.channel = channel;
	}

	@Override
	public void open(Decoder decoder) {
		this.decoder = decoder;
		open = true;
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public void write(short[] samples, int offs, int len) {
		if (!open || samples == null || len <= 0) {
			return;
		}
		if (!formatNotified) {
			formatNotified = true;
			// output format is only known after the first decoded frame
			Decoder d = decoder;
			if (d != null) {
				channel.mpegFormatKnown(d.getOutputFrequency(), d.getOutputChannels());
			}
		}
		if (channel.mpeg != null) {
			channel.mpeg.put(samples, offs, len);
		}
	}

	@Override
	public void close() {
		open = false;
		if (channel.mpeg != null) {
			channel.mpeg.close();
		}
	}

	@Override
	public void setVolume(int vol) {
		// overall gain is applied by the pump thread from the Player volume
	}

	@Override
	public int getVolume() {
		return 100;
	}

	@Override
	public void flush() {
	}

	@Override
	public int getPosition() {
		return channel.mpeg == null ? 0 : channel.mpeg.getPositionMs();
	}
}
