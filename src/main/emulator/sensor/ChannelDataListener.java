package emulator.sensor;

public interface ChannelDataListener {
	void channelDataReceived(final int p0, final DataImpl p1);

	void channelErrorReceived(final int p0, final int p1);
}
