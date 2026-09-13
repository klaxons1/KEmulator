package emulator.media.audio3d;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the OpenAL device/context and a single pump thread.
 * <p>
 * OpenAL is provided by the LWJGL <code>lwjgl-openal</code> module (3.3.6),
 * whose natives jar bundles an openal-soft build
 * (<code>openal32.dll</code> on Windows) &mdash; no system OpenAL install is
 * required.
 * <p>
 * OpenAL contexts are bound to the thread that made them current, so <b>all</b>
 * OpenAL calls in this implementation happen on the pump thread only. The rest
 * of the code (controls, players) just mutates plain Java state or queues
 * tasks via {@link #submit(Runnable)}; the pump applies them each cycle and
 * also drives the per-source gain/position/Doppler updates.
 */
public final class Audio3DContext {

	private static Audio3DContext instance;
	private static int errorsLogged;

	/** Console diagnostics (milestones + first errors only). */
	static void log(String msg) {
		System.err.println("[audio3d] " + msg);
	}

	static void logError(String msg, Throwable t) {
		if (errorsLogged < 10) {
			errorsLogged++;
			System.err.println("[audio3d] error: " + msg + " -> " + t);
			t.printStackTrace(System.err);
		}
	}

	public static synchronized Audio3DContext instance() {
		if (instance == null) {
			instance = new Audio3DContext();
		}
		return instance;
	}

	private long device;
	private long context;
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
			if (ready || context != 0) {
				return;
			}
			try {
				// LWJGL extracts the bundled openal-soft native
				// (openal32.dll on Windows) from the lwjgl-openal natives jar.
				long dev = ALC10.alcOpenDevice((ByteBuffer) null);
				if (dev == 0) {
					throw new IllegalStateException("alcOpenDevice(default) failed, error " + ALC10.alcGetError(dev));
				}
				long ctx = ALC10.alcCreateContext(dev, (IntBuffer) null);
				if (ctx == 0) {
					ALC10.alcCloseDevice(dev);
					throw new IllegalStateException("alcCreateContext failed, error " + ALC10.alcGetError(dev));
				}
				device = dev;
				context = ctx;
				ready = true;
				status = "ok";
				startPump();
			} catch (Throwable t) {
				device = 0;
				context = 0;
				ready = false;
				status = "OpenAL is not available (" + t + "). "
						+ "The lwjgl-openal natives jar (lwjgl-openal-natives-<platform>.jar, bundles openal-soft) "
						+ "must be in home/ and on the classpath, e.g. lwjgl-openal-natives-windows-x86.jar for 32-bit Windows.";
				System.err.println("*** " + status);
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
				boolean current = false;
				try {
					current = ALC10.alcMakeContextCurrent(context);
				} catch (Throwable t) {
					logError("pump: alcMakeContextCurrent", t);
				}
				log("pump started (context current=" + current + ")");
				if (current) {
					try {
						log("OpenAL: vendor=" + AL10.alGetString(AL10.AL_VENDOR)
								+ " renderer=" + AL10.alGetString(AL10.AL_RENDERER)
								+ " version=" + AL10.alGetString(AL10.AL_VERSION));
					} catch (Throwable ignored) {
					}
				}
				while (running) {
					boolean active = false;
					try {
						Runnable task;
						synchronized (taskLock) {
							while ((task = tasks.poll()) != null) {
								try {
									task.run();
								} catch (Throwable t) {
									logError("pump task", t);
								}
								active = true;
							}
						}
						for (Runnable tick : ticks) {
							try {
								tick.run();
							} catch (Throwable t) {
								logError("pump tick", t);
							}
							active = true;
						}
						if (context != 0) {
							ALC10.alcProcessContext(context);
						}
						Thread.sleep(active ? 2 : 15);
					} catch (InterruptedException e) {
						break;
					} catch (Throwable ignored) {
					}
				}
				try {
					ALC10.alcMakeContextCurrent(0);
					if (context != 0) {
						ALC10.alcDestroyContext(context);
						context = 0;
					}
					if (device != 0) {
						ALC10.alcCloseDevice(device);
						device = 0;
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
}
