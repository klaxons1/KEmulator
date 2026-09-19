package emulator;

import java.lang.reflect.Method;

/**
 * High-resolution timing helpers for stable frame pacing.
 *
 * <p>On Windows the default system timer granularity is ~15.6ms, which makes
 * {@link Thread#sleep(long)} randomly oversleep and produces visible stutter
 * in games. Requesting a 1ms multimedia timer period fixes sleep accuracy
 * globally for the JVM process.</p>
 */
public final class Timing {
	private static final int WIN_TIMER_PERIOD_MS = 1;

	private static boolean winTimerActive;

	private Timing() {
	}

	/**
	 * Improves timer granularity on Windows. No-op on other platforms.
	 * Safe to call multiple times.
	 */
	public static synchronized void init() {
		if (!Utils.win || winTimerActive) {
			return;
		}
		try {
			// Reflection is used to avoid a hard compile-time dependency on JNA.
			Class<?> funcClass = Class.forName("com.sun.jna.Function");
			Method getFunction = funcClass.getMethod("getFunction", String.class, String.class);
			Object func = getFunction.invoke(null, "winmm", "timeBeginPeriod");
			Method invokeInt = funcClass.getMethod("invokeInt", Object[].class);
			Object result = invokeInt.invoke(func, (Object) new Object[]{Integer.valueOf(WIN_TIMER_PERIOD_MS)});
			if (Integer.valueOf(0).equals(result)) {
				winTimerActive = true;
				try {
					Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
						public void run() {
							Timing.shutdown();
						}
					}, "KEmulator-TimerRestore"));
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Restores the default Windows timer period. Called on emulator exit.
	 */
	public static synchronized void shutdown() {
		if (!winTimerActive) {
			return;
		}
		winTimerActive = false;
		try {
			Class<?> funcClass = Class.forName("com.sun.jna.Function");
			Method getFunction = funcClass.getMethod("getFunction", String.class, String.class);
			Object func = getFunction.invoke(null, "winmm", "timeEndPeriod");
			Method invokeInt = funcClass.getMethod("invokeInt", Object[].class);
			invokeInt.invoke(func, (Object) new Object[]{Integer.valueOf(WIN_TIMER_PERIOD_MS)});
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Sleeps at least the given amount of nanoseconds with sub-millisecond accuracy.
	 * Combines {@link Thread#sleep(long)} with a short spin-wait tail, so the
	 * caller doesn't depend on OS timer granularity.
	 * If the thread is interrupted, the interrupt flag is restored and this method returns early.
	 */
	public static void sleepNanos(long nanos) {
		if (nanos <= 0) {
			return;
		}
		long deadline = System.nanoTime() + nanos;
		long millis = nanos / 1000000L;
		if (millis > 1) {
			try {
				Thread.sleep(millis - 1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		while (System.nanoTime() < deadline) {
			if (Thread.currentThread().isInterrupted()) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * Precise variant of {@link Thread#sleep(long)} for short (frame pacing) delays.
	 * Behaves exactly like {@link Thread#sleep(long)} for non-positive values and interrupts.
	 */
	public static void sleepMillis(long millis) throws InterruptedException {
		if (millis <= 1) {
			Thread.sleep(millis);
			return;
		}
		long deadline = System.nanoTime() + millis * 1000000L;
		Thread.sleep(millis - 1);
		while (System.nanoTime() < deadline) {
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}
		}
	}
}
