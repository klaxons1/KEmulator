package emulator.media.audio3d;

import com.sun.jna.Memory;
import emulator.javazoom.jl.decoder.Bitstream;
import emulator.javazoom.jl.decoder.Header;

import javax.microedition.amms.SpectatorImpl;
import javax.microedition.media.MediaException;
import javax.sound.sampled.AudioFormat;

import java.io.ByteArrayInputStream;

/**
 * One 3D audio channel: a single OpenAL source fed from one
 * {@link javax.microedition.media.PlayerImpl}.
 * <p>
 * The {@link Audio3DContext} pump thread drives {@link #tick()}: it tops up the
 * buffer ring and applies the JSR-234 gain formula
 * (volume x distance x directivity x obstruction) plus the Doppler pitch shift.
 * All OpenAL calls happen on the pump thread only; every OpenAL access in this
 * class is synchronized on the channel and called from the pump (or from
 * tasks submitted to it).
 * <p>
 * Two feeder flavors:
 * <ul>
 * <li>PCM (WAV/AMR): the pump pulls samples from the raw PCM captured by PlayerImpl,
 *     with pass/loop bookkeeping done in frame counts (seamless loops).</li>
 * <li>MP3: the vendored javazoom decoder thread pushes 16-bit PCM into a bounded
 *     ring buffer (via {@link ALAudioDevice}); the pump drains it into OpenAL.</li>
 * </ul>
 */
public class Source3DChannel {

	static final int BUFFER_COUNT = 8;

	private final SoundSource3DImpl owner;
	final javax.microedition.media.PlayerImpl playerImpl;

	private boolean isMpeg;
	private AL al;
	private int source;
	private int[] buffers;
	private boolean[] inQueue;
	private int bufFrames;
	private int bufBytes;
	private int sampleRate;
	private int channels;
	private int bits;
	private int frameBytes;
	private int format;
	private Memory bufMem;
	private int queued;
	private volatile boolean alReady;
	private volatile boolean detached;

	// PCM feeder data
	private byte[] pcm;
	private long totalFrames;

	// pump-owned playback state
	private long framePos;        // absolute position on the pass timeline (frames)
	private long timelineEnd;     // framePos value at which all requested passes are done
	private boolean feederComplete;
	private volatile boolean playing;

	// one-pole lowpass (ObstructionControl HF attenuation)
	private volatile double lpAlpha;
	private double[] lpState;

	// MP3 feeder
	MpegFeeder mpeg;
	volatile ALAudioDevice mpegDevice;

	Source3DChannel(SoundSource3DImpl owner, javax.microedition.media.PlayerImpl playerImpl) {
		this.owner = owner;
		this.playerImpl = playerImpl;
	}

	// ---------- creation ----------

	/**
	 * Prepares the channel; called on the thread that invoked addPlayer().
	 */
	void create() throws MediaException {
		isMpeg = playerImpl.getSequence() instanceof emulator.javazoom.jl.player.Player;
		if (isMpeg) {
			if (playerImpl.getData() == null) {
				throw new MediaException("MP3 content is not fully buffered, it cannot be played in 3D");
			}
			mpeg = new MpegFeeder(this);
			attachMpegDevice();
		} else {
			if (playerImpl.getRawPCM() == null) {
				throw new MediaException("PCM data is not available for this player (unsupported format or WAV realize failed; supported: 8/16-bit PCM WAV, AMR, MP3)");
			}
			pcm = playerImpl.getRawPCM();
			AudioFormat f = playerImpl.getRawPCMFormat();
			sampleRate = (int) f.getSampleRate();
			channels = f.getChannels();
			bits = f.getSampleSizeInBits();
			frameBytes = channels * bits / 8;
			format = (channels == 1) ? (bits == 16 ? AL.AL_FORMAT_MONO16 : AL.AL_FORMAT_MONO8)
					: (bits == 16 ? AL.AL_FORMAT_STEREO16 : AL.AL_FORMAT_STEREO8);
			totalFrames = pcm.length / frameBytes;
			if (totalFrames == 0) {
				throw new MediaException("No decodable PCM samples found");
			}
		}
		Audio3DContext.instance().submit(new Runnable() {
			@Override
			public void run() {
				initAL();
			}
		});
	}

	private void attachMpegDevice() throws MediaException {
		try {
			emulator.javazoom.jl.player.Player old = (emulator.javazoom.jl.player.Player) playerImpl.getSequence();
			if (old != null) {
				old.close();
			}
			mpegDevice = new ALAudioDevice(this);
		playerImpl.setSequence(new emulator.javazoom.jl.player.Player(
				new ByteArrayInputStream(playerImpl.getData()), mpegDevice, true));
		} catch (Exception e) {
			throw new MediaException(e);
		}
	}

	/**
	 * Fills in the MP3 output format once the first frame has been decoded.
	 */
	synchronized void mpegFormatKnown(int rate, int chCount) {
		if (!isMpeg || sampleRate != 0) {
			return;
		}
		sampleRate = rate;
		channels = chCount;
		bits = 16;
		frameBytes = channels * 2;
		format = channels == 1 ? AL.AL_FORMAT_MONO16 : AL.AL_FORMAT_STEREO16;
		totalFrames = Long.MAX_VALUE;
		// AL calls must happen on the pump thread
		Audio3DContext.instance().submit(new Runnable() {
			@Override
			public void run() {
				initAL();
			}
		});
	}

	/**
	 * Creates the OpenAL source and buffer ring. Runs on the pump thread.
	 */
	synchronized void initAL() {
		if (alReady || detached || sampleRate == 0) {
			return;
		}
		AL a = Audio3DContext.instance().al();
		if (a == null) {
			return;
		}
		al = a;
		int[] src = new int[1];
		a.alGenSources(1, src);
		source = src[0];
		bufFrames = Math.max(256, sampleRate / 50);
		bufBytes = bufFrames * frameBytes;
		buffers = new int[BUFFER_COUNT];
		a.alGenBuffers(BUFFER_COUNT, buffers);
		inQueue = new boolean[BUFFER_COUNT];
		bufMem = new Memory(bufBytes);
		lpState = new double[channels];
		a.alSourcef(source, AL.AL_REFERENCE_DISTANCE, 1000.0f);
		a.alSourcef(source, AL.AL_ROLLOFF_FACTOR, 0.0f);
		a.alSourcef(source, AL.AL_GAIN, 0.0f);
		a.alSourcef(source, AL.AL_PITCH, 1.0f);
		a.alSource3f(source, AL.AL_POSITION, 0.0f, 0.0f, 0.0f);
		alReady = true;
	}

	/**
	 * Detaches from the player; runs the cleanup on the pump thread.
	 */
	public void detach() {
		detached = true;
		playing = false;
		Audio3DContext.instance().submit(new Runnable() {
			@Override
			public void run() {
				synchronized (Source3DChannel.this) {
					if (al != null && source != 0) {
						al.alSourceStop(source);
						int q = al.alGetSourcei(source, AL.AL_BUFFERS_QUEUED);
						int[] b = new int[1];
						for (int i = 0; i < q; i++) {
							al.alSourceUnqueueBuffers(source, 1, b);
						}
						al.alDeleteSources(1, new int[]{source});
						if (buffers != null) {
							al.alDeleteBuffers(buffers.length, buffers);
						}
						source = 0;
						alReady = false;
					}
					if (mpeg != null) {
						mpeg.close();
					}
					if (bufMem != null) {
						bufMem.clear();
						bufMem = null;
					}
				}
				Audio3DContext.instance().removeTick(tickRunnable);
			}
		});
	}

	// ---------- feeding ----------

	/**
	 * Arms the channel for playback; called from the player thread before start.
	 *
	 * @param loopCount -1 for infinite, otherwise the number of passes to play
	 */
	public void startFeeding(int loopCount) {
		if (detached) {
			return;
		}
		if (!isMpeg) {
			synchronized (this) {
				// pass N spans [N*T, (N+1)*T); the first pass ends at the next
				// boundary, the remaining loopCount-1 passes are full passes
				long pass = Math.floorDiv(framePos, totalFrames);
				timelineEnd = (loopCount == -1) ? Long.MAX_VALUE : (pass + loopCount) * totalFrames;
				feederComplete = false;
			}
		}
		if (isMpeg && mpeg != null) {
			mpeg.newPass();
		}
			Audio3DContext.instance().submit(new Runnable() {
				@Override
				public void run() {
					synchronized (Source3DChannel.this) {
						if (!alReady || detached) {
							return;
						}
						// discard stale buffers left over from a previous run
						int[] b = new int[1];
						int q = al.alGetSourcei(source, AL.AL_BUFFERS_QUEUED);
						for (int i = 0; i < q; i++) {
							al.alSourceUnqueueBuffers(source, 1, b);
							if (inQueue != null && inQueue[b[0]]) {
								inQueue[b[0]] = false;
							}
						}
						queued = 0;
						if (pendingSeek >= 0) {
							applySeekLocked();
						}
						playing = true;
						resetLowpass();
						if (!isMpeg && queued == 0) {
							fillPcmBufferLocked(); // prime the first buffer immediately
						}
					}
					Audio3DContext.instance().addTick(tickRunnable);
				}
			});
	}

	/**
	 * Marks the current MP3 pass as stream-exhausted (javazoom play() returned
	 * with the stream fully decoded). The pump finishes the pass once the tail
	 * of the ring and the buffer queue have been drained.
	 */
	public void mpegPassEnded() {
		if (mpeg != null) {
			mpeg.passEnded();
		}
	}

	public void stopFeeding() {
		playing = false;
		Audio3DContext.instance().submit(new Runnable() {
			@Override
			public void run() {
				synchronized (Source3DChannel.this) {
					if (al != null && alReady && source != 0) {
						al.alSourceStop(source);
					}
				}
			}
		});
	}

	/**
	 * Rebuilds the javazoom player for the next MP3 pass (called from the player thread).
	 */
	public void newMpegPass() {
		if (detached || !isMpeg) {
			return;
		}
		try {
			emulator.javazoom.jl.player.Player old = (emulator.javazoom.jl.player.Player) playerImpl.getSequence();
			if (old != null) {
				old.close();
			}
			ALAudioDevice dev = new ALAudioDevice(this);
			mpegDevice = dev;
			playerImpl.setSequence(new emulator.javazoom.jl.player.Player(
					new ByteArrayInputStream(playerImpl.getData()), dev, true));
			if (mpeg != null) {
				mpeg.newPass();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public long getMediaTimeMicros() {
		if (detached) {
			return 0;
		}
		if (isMpeg) {
			return mpeg == null ? 0 : mpeg.getMediaTimeMicros();
		}
		long f = Math.floorMod(framePos, totalFrames);
		if (sampleRate == 0) {
			return 0;
		}
		return (long) (f * 1_000_000.0 / sampleRate);
	}

	private long pendingSeek = -1;

	public void setMediaTimeMicros(long t) throws MediaException {
		if (detached) {
			return;
		}
		if (isMpeg) {
			mpegSeek(t);
			return;
		}
		long tFrames = (long) (t / 1_000_000.0 * sampleRate);
		if (tFrames < 0) {
			tFrames = 0;
		}
		if (tFrames >= totalFrames) {
			tFrames = totalFrames - 1;
		}
		synchronized (this) {
			long passBase = Math.floorDiv(framePos, totalFrames) * totalFrames;
			framePos = passBase + tFrames;
			pendingSeek = tFrames;
		}
	}

	/**
	 * MP3 seek: rebuilds the javazoom player and skips to the requested time.
	 * Runs on the calling thread (it may block on decoding).
	 */
	private void mpegSeek(long t) throws MediaException {
		try {
			boolean wasPlaying = playerImpl.getState() == javax.microedition.media.Player.STARTED;
			if (wasPlaying) {
				playerImpl.stop();
			}
			Header header = null;
			try {
				Bitstream bs = new Bitstream(new ByteArrayInputStream(playerImpl.getData()));
				header = bs.readFrame();
				bs.close();
			} catch (Exception ignored) {
			}
			emulator.javazoom.jl.player.Player old = (emulator.javazoom.jl.player.Player) playerImpl.getSequence();
			if (old != null) {
				old.close();
			}
			ALAudioDevice dev = new ALAudioDevice(this);
			mpegDevice = dev;
			emulator.javazoom.jl.player.Player p2 = new emulator.javazoom.jl.player.Player(
					new ByteArrayInputStream(playerImpl.getData()), dev, true);
			long seekMs = t / 1000;
			if (seekMs > 0 && header != null) {
				try {
					p2.skip((int) seekMs, header);
				} catch (Exception ignored) {
				}
			}
			playerImpl.setSequence(p2);
			if (mpeg != null) {
				mpeg.newPass();
			}
			if (wasPlaying) {
				playerImpl.start();
			}
		} catch (Exception e) {
			throw new MediaException(e);
		}
	}

	// ---------- pump tick ----------

	private final Runnable tickRunnable = new Runnable() {
		@Override
		public void run() {
			tick();
		}
	};

	private void tick() {
		if (detached) {
			Audio3DContext.instance().removeTick(tickRunnable);
			return;
		}
		if (playerImpl.getState() == javax.microedition.media.Player.CLOSED) {
			detach();
			return;
		}
		AL a = Audio3DContext.instance().al();
		if (a == null) {
			return;
		}
		synchronized (this) {
			if (!alReady || source == 0) {
				return;
			}
			updateLowpassLocked();
			if (pendingSeek >= 0) {
				applySeekLocked();
			}
			if (playing) {
				if (isMpeg) {
					drainMpegLocked();
				} else {
					int guard = 0;
					while (queued < BUFFER_COUNT && !feederComplete && guard++ < BUFFER_COUNT) {
						fillPcmBufferLocked();
					}
				}
			}
			boolean done = false;
			if (isMpeg) {
				done = mpeg != null && mpeg.isComplete() && queued == 0;
			} else {
				done = feederComplete && queued == 0;
			}
			if (playing && done) {
				playing = false;
				al.alSourceStop(source);
				Audio3DContext.instance().removeTick(tickRunnable);
				playerImpl.notifyCompleted();
				return;
			}
			// ---- gain / pitch / position ----
			double gain = 0.0;
			double pitch = 1.0;
			if (playing) {
				double[] fx = new double[3];
				double[] fu = new double[3];
				Vec3.orientationFromAngles(owner.p.heading, owner.p.pitch, owner.p.roll, fx, fu);
				double sx = owner.p.x / 1000.0, sy = owner.p.y / 1000.0, sz = owner.p.z / 1000.0;
				double lx = SpectatorImpl.x / 1000.0, ly = SpectatorImpl.y / 1000.0, lz = SpectatorImpl.z / 1000.0;
				double dx = sx - lx, dy = sy - ly, dz = sz - lz;
				double R = Vec3.length(dx, dy, dz);
				// macroscopic source: use the nearest surface point for attenuation
				double Reff = R;
				int szX = owner.p.sizeX, szY = owner.p.sizeY, szZ = owner.p.sizeZ;
				if ((szX | szY | szZ) != 0 && R > 1e-6) {
					double vx = dx / R, vy = dy / R, vz = dz / R;
					double vr = vx * (fu[0] * fx[2] - fu[2] * fx[1]) + vy * (fu[2] * fx[0] - fu[0] * fx[2]) + vz * (fu[0] * fx[1] - fu[1] * fx[0]);
					double vu = vx * fu[0] + vy * fu[1] + vz * fu[2];
					double vf = vx * fx[0] + vy * fx[1] + vz * fx[2];
					double extent = (szX * Math.abs(vr) + szY * Math.abs(vu) + szZ * Math.abs(vf)) / 1000.0;
					Reff = Math.max(R - extent, 0.05);
				}
				// distance gain (JSR-234 DistanceAttenuationControl formula)
				double g = 1.0;
				double Rmin = Math.max(owner.p.minDistance, 1) / 1000.0;
				double Rmax = owner.p.maxDistance / 1000.0;
				double ro = owner.p.rolloffFactor / 1000.0;
				if (Rmax > Rmin) {
					if (Reff > Rmin) {
						if (Reff >= Rmax) {
							g = owner.p.muteAfterMax ? 0.0 : Math.pow(Rmin / Rmax, ro);
						} else {
							g = Math.pow(Rmin / Reff, ro);
						}
					}
				}
				// directivity
				if (R > 1e-9) {
					double ang = Vec3.angleBetween(fx, new double[]{dx / R, dy / R, dz / R});
					double m = owner.p.minAngle, M = owner.p.maxAngle;
					if (m != 360 && ang >= m) {
						double rear;
						if (owner.p.rearLevel == Integer.MIN_VALUE) {
							rear = 0.0;
						} else {
							rear = Math.pow(10.0, owner.p.rearLevel / 2000.0);
						}
						if (ang >= M) {
							g *= rear;
						} else if (M > m) {
							g *= 1.0 + (rear - 1.0) * (ang - m) / (M - m);
						} else {
							g *= rear;
						}
					}
				}
				// obstruction overall level
				g *= Math.pow(10.0, owner.p.obsLevel / 2000.0);
				// player volume
				g *= playerImpl.getLevel() / 100.0;
				if (g > 1.0) {
					g = 1.0;
				}
				gain = g;
				// Doppler pitch shift
				double c = 340.0;
				if (R > 1e-4) {
					double px = dx / R, py = dy / R, pz = dz / R;
					double lvx = SpectatorImpl.vx / 1000.0, lvy = SpectatorImpl.vy / 1000.0, lvz = SpectatorImpl.vz / 1000.0;
					double u = lvx * px + lvy * py + lvz * pz;
					double v = 0.0;
					if (owner.p.dopplerEnabled) {
						double svx = owner.p.dvx / 1000.0, svy = owner.p.dvy / 1000.0, svz = owner.p.dvz / 1000.0;
						v = svx * px + svy * py + svz * pz;
					}
					double den = c + v;
					if (den > c * 0.01) {
						pitch = (c + u) / den;
						if (pitch < 0.5) {
							pitch = 0.5;
						} else if (pitch > 2.0) {
							pitch = 2.0;
						}
					}
				}
			}
			al.alSourcef(source, AL.AL_GAIN, (float) gain);
			al.alSourcef(source, AL.AL_PITCH, (float) pitch);
			al.alSource3f(source, AL.AL_POSITION,
					(float) (owner.p.x / 1000.0), (float) (owner.p.y / 1000.0), (float) (owner.p.z / 1000.0));
		}
	}

	// ---------- PCM feeder (pump thread, channel locked) ----------

	private void fillPcmBufferLocked() {
		if (queued >= BUFFER_COUNT || feederComplete) {
			return;
		}
		int[] b = new int[1];
		int processed = al.alGetSourcei(source, AL.AL_BUFFERS_PROCESSED);
		for (int i = 0; i < processed; i++) {
			al.alSourceUnqueueBuffers(source, 1, b);
			if (inQueue[b[0]] != false) {
				inQueue[b[0]] = false;
				if (queued > 0) {
					queued--;
				}
			}
		}
		if (queued >= BUFFER_COUNT || feederComplete) {
			return;
		}
		long take = Math.min(bufFrames, timelineEnd - framePos);
		if (take <= 0) {
			feederComplete = true;
			return;
		}
		long srcFrame = Math.floorMod(framePos, totalFrames);
		bufMem.clear();
		int fb = frameBytes;
		for (long i = 0; i < take; i++) {
			long f = Math.floorMod(srcFrame + i, totalFrames);
			int off = (int) (f * fb);
			if (bits == 16) {
				for (int c = 0; c < channels; c++) {
					int s = (pcm[off + c * 2] & 0xFF) | (pcm[off + c * 2 + 1] << 8);
					s = filterSample(s, c);
					bufMem.setShort((int) (i * fb + c * 2), (short) s);
				}
			} else {
				for (int c = 0; c < channels; c++) {
					int s = pcm[off + c] & 0xFF;
					s = filterSample8(s, c);
					bufMem.setByte((int) (i * fb + c), (byte) s);
				}
			}
		}
		int pad = bufBytes - (int) (take * fb);
		if (pad > 0) {
			if (bits == 8) {
				for (int i = 0; i < pad; i++) {
					bufMem.setByte((int) (take * fb) + i, (byte) 128);
				}
			}
			// 16-bit silence is zero (Memory was cleared)
		}
		int slot = -1;
		for (int i = 0; i < BUFFER_COUNT; i++) {
			if (!inQueue[i]) {
				slot = i;
				break;
			}
		}
		if (slot == -1) {
			return;
		}
		al.alBufferData(buffers[slot], format, bufMem, bufBytes, sampleRate);
		int[] q = new int[]{buffers[slot]};
		al.alSourceQueueBuffers(source, 1, q);
		inQueue[slot] = true;
		queued++;
		al.alSourcePlay(source);
		framePos += bufFrames;
		if (framePos >= timelineEnd) {
			feederComplete = true;
		}
	}

	private void applySeekLocked() {
		long tFrames = pendingSeek;
		pendingSeek = -1;
		int[] b = new int[1];
		int q = al.alGetSourcei(source, AL.AL_BUFFERS_QUEUED);
		for (int i = 0; i < q; i++) {
			al.alSourceUnqueueBuffers(source, 1, b);
			if (inQueue[b[0]] != false) {
				inQueue[b[0]] = false;
			}
		}
		queued = 0;
		long passBase = Math.floorDiv(framePos, totalFrames) * totalFrames;
		framePos = passBase + tFrames;
		resetLowpass();
	}

	// ---------- MP3 feeder (pump thread drains; javazoom thread pushes) ----------

	private void drainMpegLocked() {
		int guard = 0;
		while (queued < BUFFER_COUNT && !feederComplete && guard++ < BUFFER_COUNT) {
			if (!mpeg.drainOneLocked()) {
				break;
			}
		}
		if (mpeg.isComplete()) {
			feederComplete = true;
		}
	}

	/**
	 * Bounded byte ring fed by the javazoom decode thread via {@link ALAudioDevice}.
	 */
	static class MpegFeeder {
		private final Source3DChannel ch;
		private final byte[] ring = new byte[256 * 1024];
		private final Object lock = new Object();
		private int head;
		private int tail;
		private int count;
		private volatile boolean accepting;
		private volatile boolean finished;
		private long framesDrained;

		MpegFeeder(Source3DChannel ch) {
			this.ch = ch;
		}

		void newPass() {
			synchronized (lock) {
				head = 0;
				tail = 0;
				count = 0;
				lock.notifyAll();
			}
			finished = false;
			framesDrained = 0;
			accepting = true;
		}

		void close() {
			synchronized (lock) {
				accepting = false;
				lock.notifyAll();
			}
		}

		/**
		 * The decoder has reached the end of the stream for this pass.
		 */
		void passEnded() {
			int fb = ch.frameBytes;
			if (fb > 0) {
				// a torn tail shorter than one frame could never be drained;
				// discard it so isComplete() can become true
				synchronized (lock) {
					while (count > 0 && count < fb) {
						head = (head + 1) % ring.length;
						count--;
					}
					lock.notifyAll();
				}
			}
			finished = true;
		}

		boolean hasData() {
			synchronized (lock) {
				return count > 0;
			}
		}

		/**
		 * Pushes decoded samples; called from the javazoom decode thread.
		 */
		void put(short[] samples, int offs, int len) {
			if (!accepting || len <= 0 || ch.detached) {
				return;
			}
			int n = len * 2;
			byte[] tmp = new byte[n];
			for (int i = 0; i < len; i++) {
				short s = samples[offs + i];
				tmp[i * 2] = (byte) (s & 0xFF);
				tmp[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
			}
			long waitStart = System.currentTimeMillis();
			synchronized (lock) {
				while (count + n > ring.length) {
					if (!accepting || ch.detached) {
						return;
					}
					try {
						lock.wait(20);
					} catch (InterruptedException e) {
						return;
					}
					if (System.currentTimeMillis() - waitStart > 3000) {
						return; // drop rather than jam the decoder thread
					}
				}
				for (int i = 0; i < n; i++) {
					ring[tail] = tmp[i];
					tail = (tail + 1) % ring.length;
				}
				count += n;
				lock.notifyAll();
			}
		}

		boolean isComplete() {
			return finished && !hasData();
		}

		public long getMediaTimeMicros() {
			if (ch.sampleRate == 0) {
				return 0;
			}
			long frames = framesDrained + count / ch.frameBytes;
			return (long) (frames * 1_000_000.0 / ch.sampleRate);
		}

		int getPositionMs() {
			if (ch.sampleRate == 0) {
				return 0;
			}
			long frames = framesDrained + count / ch.frameBytes;
			return (int) (frames * 1000L / ch.sampleRate);
		}

		/**
		 * Pumps one buffer from the ring into OpenAL. Pump thread, channel locked.
		 *
		 * @return false when there is no data to drain right now
		 */
		boolean drainOneLocked() {
			AL al = ch.al;
			int[] b = new int[1];
			int processed = al.alGetSourcei(ch.source, AL.AL_BUFFERS_PROCESSED);
			for (int i = 0; i < processed; i++) {
				al.alSourceUnqueueBuffers(ch.source, 1, b);
				if (ch.inQueue[b[0]] != false) {
					ch.inQueue[b[0]] = false;
					if (ch.queued > 0) {
						ch.queued--;
					}
				}
			}
			int avail;
			synchronized (lock) {
				avail = count;
				if (avail == 0) {
					return false;
				}
			}
			int take = Math.min(ch.bufBytes, avail);
			take -= take % ch.frameBytes;
			if (take <= 0) {
				return false;
			}
			byte[] data = new byte[take];
			synchronized (lock) {
				for (int i = 0; i < take; i++) {
					data[i] = ring[head];
					head = (head + 1) % ring.length;
					count--;
				}
				lock.notifyAll();
			}
			int slot = -1;
			for (int i = 0; i < BUFFER_COUNT; i++) {
				if (!ch.inQueue[i]) {
					slot = i;
					break;
				}
			}
			if (slot == -1) {
				return true;
			}
			ch.bufMem.clear();
			int nFrames = take / ch.frameBytes;
			int fb = ch.frameBytes;
			for (int i = 0; i < nFrames; i++) {
				for (int c = 0; c < ch.channels; c++) {
					int s = (data[i * fb + c * 2] & 0xFF) | (data[i * fb + c * 2 + 1] << 8);
					s = ch.filterSample(s, c);
					ch.bufMem.setShort(i * fb + c * 2, (short) s);
				}
			}
			al.alBufferData(ch.buffers[slot], ch.format, ch.bufMem, ch.bufBytes, ch.sampleRate);
			int[] q = new int[]{ch.buffers[slot]};
			al.alSourceQueueBuffers(ch.source, 1, q);
			ch.inQueue[slot] = true;
			ch.queued++;
			framesDrained += nFrames;
			al.alSourcePlay(ch.source);
			return true;
		}
	}

	// ---------- lowpass ----------

	private void updateLowpassLocked() {
		int hf = owner.p.obsHFLevel;
		if (hf == 0) {
			if (lpAlpha != 0.0) {
				lpAlpha = 0.0;
				resetLowpass();
			}
			return;
		}
		double g5k = Math.pow(10.0, hf / 2000.0); // transmission at 5000 Hz (<= 1)
		double fc;
		if (g5k <= 1e-4) {
			fc = 10.0; // effectively muffled
		} else {
			fc = 5000.0 / Math.sqrt(1.0 / (g5k * g5k) - 1.0);
			if (fc > 44100.0) {
				fc = 44100.0;
			}
		}
		lpAlpha = 1.0 - Math.exp(-2.0 * Math.PI * fc / sampleRate);
	}

	private void resetLowpass() {
		if (lpState != null) {
			for (int i = 0; i < lpState.length; i++) {
				lpState[i] = 0.0;
			}
		}
	}

	/**
	 * One-pole lowpass, 16-bit. Called with the channel lock held (pump thread).
	 */
	int filterSample(int s, int c) {
		double a = lpAlpha;
		if (a == 0.0 || lpState == null) {
			return s;
		}
		double y = lpState[c] + a * (s - lpState[c]);
		lpState[c] = y;
		return (int) (y + 0.5);
	}

	private int filterSample8(int s, int c) {
		double a = lpAlpha;
		if (a == 0.0 || lpState == null) {
			return s;
		}
		double y = lpState[c] + a * (s - lpState[c]);
		lpState[c] = y;
		int r = (int) (y + 0.5);
		return r > 255 ? 255 : r < 0 ? 0 : r;
	}
}
