package emulator.media.audio3d;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the OpenAL device/context and a single pump thread.
 * <p>
 * OpenAL contexts are bound to the thread that made them current, so <b>all</b>
 * OpenAL calls in this implementation happen on the pump thread only. The rest
 * of the code (controls, players) just mutates plain Java state or queues
 * tasks via {@link #submit(Runnable)}; the pump applies them each cycle and
 * also drives the per-source gain/position/Doppler updates.
 */
public final class Audio3DContext {

	private static Audio3DContext instance;

	public static synchronized Audio3DContext instance() {
		if (instance == null) {
			instance = new Audio3DContext();
		}
		return instance;
	}

	private AL al;
	private Pointer device;
	private Pointer context;
	private volatile boolean ready;
	private volatile String status = "not initialized";

	private final Object initLock = new Object();
	private final Object taskLock = new Object();
	private final LinkedList<Runnable> tasks = new LinkedList<Runnable>();
	private final CopyOnWriteArrayList<Runnable> ticks = new CopyOnWriteArrayList<Runnable>();
	private Thread pump;
	private volatile boolean running;

	private Audio3DContext() {
	}

	public boolean isReady() {
		return ready;
	}

	/**
	 * @return a human readable explanation if the context is not ready.
	 */
	public String status() {
		return ready ? "ok" : status;
	}

	/**
	 * Registers a per-cycle callback executed on the pump thread.
	 */
	public void addTick(Runnable tick) {
		ticks.addIfAbsent(tick);
	}

	public void removeTick(Runnable tick) {
		ticks.remove(tick);
	}

	/**
	 * Tries to open the default OpenAL device and create a context.
	 * May be called repeatedly; after a successful or failed attempt it is
	 * not retried within this JVM run (a failed library load can be retried
	 * only if the whole context object is recreated).
	 */
	public void ensureStarted() {
		synchronized (initLock) {
			if (ready || al != null) {
				return;
			}
			try {
				String os = System.getProperty("os.name", "").toLowerCase();
				String lib;
				if (os.contains("win")) {
					lib = "openal32";
				} else if (os.contains("mac")) {
					lib = "/System/Library/Frameworks/OpenAL.framework/OpenAL";
				} else {
					lib = "openal";
				}
				AL loaded = Native.load(lib, AL.class);
				Pointer dev = loaded.alcOpenDevice(null);
				if (dev == null) {
					throw new IllegalStateException("alcOpenDevice(default) failed, error " + loaded.alcGetError(dev));
				}
				Pointer ctx = loaded.alcCreateContext(dev, null);
				if (ctx == null) {
					loaded.alcCloseDevice(dev);
					throw new IllegalStateException("alcCreateContext failed, error " + loaded.alcGetError(dev));
				}
				al = loaded;
				device = dev;
				context = ctx;
				ready = true;
				status = "ok";
				startPump();
			} catch (Throwable t) {
				al = null;
				device = null;
				context = null;
				ready = false;
				status = "OpenAL is not available (" + t + "). On Windows install OpenAL Soft and put openal32.dll next to KEmulator.jar or on the library path; on Linux install libopenal.";
			}
		}
	}

	private void startPump() {
		if (pump != null) {
			return;
		}
		running = true;
		pump = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					al.alcMakeContextCurrent(context);
				} catch (Throwable ignored) {
				}
				while (running) {
					boolean active = false;
					try {
						Runnable task;
						synchronized (taskLock) {
							while ((task = tasks.poll()) != null) {
								try {
									task.run();
								} catch (Throwable ignored) {
								}
								active = true;
							}
						}
						for (Runnable tick : ticks) {
							try {
								tick.run();
							} catch (Throwable ignored) {
							}
							active = true;
						}
						if (context != null) {
							al.alcProcessContext(context);
						}
						Thread.sleep(active ? 2 : 15);
					} catch (InterruptedException e) {
						break;
					} catch (Throwable ignored) {
					}
				}
				try {
					al.alcMakeContextCurrent(null);
					if (context != null) {
						al.alcDestroyContext(context);
						context = null;
					}
					if (device != null) {
						al.alcCloseDevice(device);
						device = null;
					}
				} catch (Throwable ignored) {
				}
				ready = false;
			}
		}, "Audio3D-Pump");
		pump.setDaemon(true);
		pump.start();
	}

	/**
	 * Queues a task for the pump thread (ignored when the context is not ready).
	 */
	public void submit(Runnable task) {
		if (!ready) {
			return;
		}
		synchronized (taskLock) {
			tasks.add(task);
		}
	}

	/**
	 * The JNA library handle, valid only when {@link #isReady()}.
	 */
	public AL al() {
		return al;
	}
}
