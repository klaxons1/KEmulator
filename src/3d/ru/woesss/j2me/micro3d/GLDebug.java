/*
 * Copyright 2022-2023 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.micro3d;

import emulator.AppSettings;
import emulator.graphics3D.lwjgl.Emulator3D;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.Buffer;

/**
 * Structured GL diagnostics for the MascotCapsule/LWJGL renderer.
 * <p>
 * OpenGL error flags are sticky and {@code glGetError()} returns only one
 * flag per call. A single unchecked error from an earlier texture upload can
 * therefore be mislabeled by a later {@code checkGlError("texImage2D")}.
 * The helpers here drain all pending errors, name them, and dump the GL state
 * that matters for texture uploads. Verbose per-frame traces are opt-in via
 * {@link AppSettings#mascotGlDebug}.
 */
final class GLDebug {
	private static final String PREFIX = "[MC3D-GL] ";
	private static final int MAX_DRAINED_ERRORS = 32;
	private static final int MAX_IDENTICAL_ERRORS = 3;
	private static volatile String lastFrame = "none";
	private static volatile String lastTextureUpload = "none";
	private static volatile boolean suppressionReported;

	private GLDebug() {
	}

	static boolean verbose() {
		return AppSettings.mascotGlDebug;
	}

	static void trace(String message) {
		if (verbose()) {
			log(message);
		}
	}

	static void log(String message) {
		System.err.println(PREFIX + message);
	}

	/**
	 * Force-closing the emulator destroys the GL context while the M3G thread
	 * can still have a queued frame. In that state glGetError() may repeatedly
	 * return GL_INVALID_OPERATION and glGetString()/glGetInteger() return
	 * null/zero. Report that condition once, clear the pending flags, and let
	 * callers skip GL work instead of throwing or flooding the log.
	 */
	static boolean isShuttingDown() {
		return Emulator3D.isExiting();
	}

	static boolean skipShutdownOrContextLoss(String operation) {
		boolean exiting = isShuttingDown();
		boolean contextLost = !exiting && contextUnavailable();
		if (!exiting && !contextLost) {
			suppressionReported = false;
			return false;
		}
		if (!suppressionReported) {
			suppressionReported = true;
			String reason = exiting ? "emulator is shutting down" : "GL context is no longer current";
			log(operation + ": " + reason
					+ "; suppressing shutdown/context-loss GL errors and skipping GL work");
		}
		clearErrors();
		return true;
	}

	private static boolean contextUnavailable() {
		try {
			String version = GL11.glGetString(GL11.GL_VERSION);
			if (version == null || version.isEmpty()) {
				return true;
			}
			return GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) <= 0;
		} catch (Throwable t) {
			return true;
		}
	}

	/** Drain all pending GL errors. Throw on the first one while the context is alive. */
	static void check(String operation) {
		if (skipShutdownOrContextLoss(operation)) {
			return;
		}
		drain(operation, true);
	}

	/**
	 * Fail on errors that were already pending before an operation, instead of
	 * letting a later check mislabel them as coming from that operation.
	 */
	static void checkStale(String operation) {
		if (skipShutdownOrContextLoss(operation)) {
			return;
		}
		drain(operation + " [stale before operation]", true);
	}

	/** Log detailed, opt-in per-frame traces without flooding at every FPS tick. */
	static boolean frameTrace(long frame) {
		return verbose() && (frame <= 30 || frame % 60 == 0);
	}

	static void frame(long frame, String message) {
		String line = "frame " + frame + ": " + message;
		lastFrame = line;
		if (frameTrace(frame)) {
			log(line);
		}
	}

	/** Always print a frame failure, even when verbose frame tracing is off. */
	static void frameError(long frame, String message) {
		String line = "frame " + frame + ": " + message;
		lastFrame = line;
		log("[ERROR] " + line);
	}

	/** Dump GL state as part of an opt-in frame trace. */
	static void frameState(long frame, String operation) {
		if (frameTrace(frame)) {
			dumpState("frame " + frame + ": " + operation);
		}
	}

	private static int drain(String operation, boolean fail) {
		int first = GL11.GL_NO_ERROR;
		int count = 0;
		int previous = -1;
		int identical = 0;
		boolean stopped = false;
		int error;
		while (count < MAX_DRAINED_ERRORS && (error = GL11.glGetError()) != GL11.GL_NO_ERROR) {
			if (first == GL11.GL_NO_ERROR) {
				first = error;
			}
			count++;
			if (error == previous) {
				identical++;
			} else {
				previous = error;
				identical = 1;
			}
			log(operation + ": glError " + error + " (" + errorName(error) + ")");
			// A destroyed/no-longer-current context can return the same error
			// forever. Three identical lines are enough to identify that case.
			if (identical >= MAX_IDENTICAL_ERRORS) {
				log(operation + ": stopped draining after " + count
						+ " errors (repeated " + errorName(error) + ")");
				stopped = true;
				break;
			}
		}
		if (!stopped && count == MAX_DRAINED_ERRORS) {
			log(operation + ": stopped draining after " + count + " errors");
		}
		if (first != GL11.GL_NO_ERROR) {
			log(operation + ": " + count + " GL error(s) drained; first=" + errorName(first));
			boolean contextLost = contextUnavailable();
			if (contextLost) {
				log(operation + ": GL state unavailable (context lost or no current context)");
			} else {
				dumpState(operation);
			}
			// Clear errors raised by the state/context queries above, too.
			clearErrors();
			if (fail && !contextLost && !isShuttingDown()) {
				throw new RuntimeException(operation + ": glError " + first + " (" + errorName(first) + ")");
			}
		}
		return first;
	}

	private static void clearErrors() {
		try {
			int previous = -1;
			int identical = 0;
			for (int i = 0; i < MAX_DRAINED_ERRORS; i++) {
				int error = GL11.glGetError();
				if (error == GL11.GL_NO_ERROR) {
					break;
				}
				if (error == previous && ++identical >= MAX_IDENTICAL_ERRORS) {
					break;
				}
				if (error != previous) {
					previous = error;
					identical = 1;
				}
			}
		} catch (Throwable t) {
			// A missing native/context during shutdown is expected; keep it out
			// of the default error stream. Verbose mode can still show it.
			trace("glGetError cleanup failed: " + t);
		}
	}

	static void logContext(String operation) {
		if (!verbose() || skipShutdownOrContextLoss(operation)) {
			return;
		}
		try {
			GLCapabilities caps = capabilities();
			log(operation + ": vendor=" + safeString(GL11.GL_VENDOR)
					+ ", renderer=" + safeString(GL11.GL_RENDERER)
					+ ", version=" + safeString(GL11.GL_VERSION)
					+ ", maxTextureSize=" + intState(GL11.GL_MAX_TEXTURE_SIZE));
			if (caps != null) {
				log(operation + ": caps OpenGL20=" + caps.OpenGL20
						+ ", OpenGL21=" + caps.OpenGL21
						+ ", OpenGL30=" + caps.OpenGL30
						+ ", GL_ARB_texture_non_power_of_two=" + caps.GL_ARB_texture_non_power_of_two);
			}
		} finally {
			// Debug queries must not leave an error flag that is misattributed
			// to the next texture upload.
			clearErrors();
		}
	}

	static void dumpState(String operation) {
		try {
			if (contextUnavailable()) {
				log(operation + ": GL state unavailable (context lost or no current context)"
						+ ", lastFrame=" + lastFrame
						+ ", lastTextureUpload=" + lastTextureUpload);
				return;
			}
			GLCapabilities caps = capabilities();
			StringBuilder sb = new StringBuilder(256);
			sb.append(operation).append(": GL state");
			sb.append(" vendor=").append(safeString(GL11.GL_VENDOR));
			sb.append(", renderer=").append(safeString(GL11.GL_RENDERER));
			sb.append(", version=").append(safeString(GL11.GL_VERSION));
			sb.append(", maxTextureSize=").append(intState(GL11.GL_MAX_TEXTURE_SIZE));
			sb.append(", activeUnit=").append(activeTextureUnit());
			sb.append(", texture2D=").append(intState(GL11.GL_TEXTURE_BINDING_2D));
			sb.append(", unpackAlignment=").append(intState(GL11.GL_UNPACK_ALIGNMENT));
			sb.append(", unpackRowLength=").append(intState(GL11.GL_UNPACK_ROW_LENGTH));
			sb.append(", unpackSkipRows=").append(intState(GL11.GL_UNPACK_SKIP_ROWS));
			sb.append(", unpackSkipPixels=").append(intState(GL11.GL_UNPACK_SKIP_PIXELS));
			if (caps != null) {
				if (caps.OpenGL20) {
					sb.append(", currentProgram=").append(intState(GL20.GL_CURRENT_PROGRAM));
				}
				if (caps.OpenGL21) {
					sb.append(", pixelUnpackBuffer=").append(intState(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
				}
				sb.append(", NPOT=").append(caps.GL_ARB_texture_non_power_of_two);
			}
			sb.append(", lastFrame=").append(lastFrame);
			sb.append(", lastTextureUpload=").append(lastTextureUpload);
			log(sb.toString());
		} finally {
			clearErrors();
		}
	}

	static void logTextureUpload(String label, int target, int level, int internalFormat,
			int width, int height, int border, int format, int type,
			Buffer data, int textureId) {
		StringBuilder sb = new StringBuilder(256);
		sb.append(label).append(": glTexImage2D");
		sb.append(" target=").append(targetName(target));
		sb.append(", level=").append(level);
		sb.append(", internalFormat=").append(formatName(internalFormat));
		sb.append(", size=").append(width).append('x').append(height);
		sb.append(", border=").append(border);
		sb.append(", format=").append(formatName(format));
		sb.append(", type=").append(typeName(type));
		sb.append(", textureId=").append(textureId);
		sb.append(", data=").append(bufferInfo(data));
		lastTextureUpload = sb.toString();
		if (!verbose()) {
			return;
		}
		try {
			sb.append(", ").append(shortState());
			log(sb.toString());
		} finally {
			// State queries above can themselves raise errors on old contexts;
			// never let the diagnostics create the error they are measuring.
			clearErrors();
		}
	}

	static int activeTextureUnit() {
		try {
			return GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
		} catch (Throwable t) {
			return -1;
		}
	}

	private static String shortState() {
		if (contextUnavailable()) {
			return "contextLost=true";
		}
		GLCapabilities caps = capabilities();
		StringBuilder sb = new StringBuilder(128);
		sb.append("activeUnit=").append(activeTextureUnit());
		sb.append(", texture2D=").append(intState(GL11.GL_TEXTURE_BINDING_2D));
		sb.append(", unpackAlignment=").append(intState(GL11.GL_UNPACK_ALIGNMENT));
		sb.append(", unpackRowLength=").append(intState(GL11.GL_UNPACK_ROW_LENGTH));
		if (caps != null && caps.OpenGL21) {
			sb.append(", pixelUnpackBuffer=").append(intState(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
		}
		return sb.toString();
	}

	private static GLCapabilities capabilities() {
		try {
			return GL.getCapabilities();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String intState(int pname) {
		try {
			return Integer.toString(GL11.glGetInteger(pname));
		} catch (Throwable t) {
			return "<error:" + t.getClass().getSimpleName() + ">";
		}
	}

	private static String safeString(int pname) {
		try {
			String s = GL11.glGetString(pname);
			return s == null ? "<null>" : s;
		} catch (Throwable t) {
			return "<error:" + t.getClass().getSimpleName() + ">";
		}
	}

	private static String bufferInfo(Buffer data) {
		if (data == null) {
			return "null";
		}
		try {
			return data.getClass().getSimpleName()
					+ "[pos=" + data.position()
					+ ", remaining=" + data.remaining()
					+ ", capacity=" + data.capacity() + "]";
		} catch (Throwable t) {
			return "<error:" + t.getClass().getSimpleName() + ">";
		}
	}

	private static String errorName(int error) {
		switch (error) {
			case GL11.GL_NO_ERROR:
				return "GL_NO_ERROR";
			case GL11.GL_INVALID_ENUM:
				return "GL_INVALID_ENUM";
			case GL11.GL_INVALID_VALUE:
				return "GL_INVALID_VALUE";
			case GL11.GL_INVALID_OPERATION:
				return "GL_INVALID_OPERATION";
			case GL11.GL_STACK_OVERFLOW:
				return "GL_STACK_OVERFLOW";
			case GL11.GL_STACK_UNDERFLOW:
				return "GL_STACK_UNDERFLOW";
			case GL11.GL_OUT_OF_MEMORY:
				return "GL_OUT_OF_MEMORY";
			case GL30.GL_INVALID_FRAMEBUFFER_OPERATION:
				return "GL_INVALID_FRAMEBUFFER_OPERATION";
			default:
				return "0x" + Integer.toHexString(error);
		}
	}

	private static String targetName(int target) {
		switch (target) {
			case GL11.GL_TEXTURE_2D:
				return "GL_TEXTURE_2D";
			case GL11.GL_PROXY_TEXTURE_2D:
				return "GL_PROXY_TEXTURE_2D";
			default:
				return "0x" + Integer.toHexString(target);
		}
	}

	private static String formatName(int format) {
		switch (format) {
			case GL11.GL_ALPHA:
				return "GL_ALPHA";
			case GL11.GL_LUMINANCE:
				return "GL_LUMINANCE";
			case GL11.GL_LUMINANCE_ALPHA:
				return "GL_LUMINANCE_ALPHA";
			case GL11.GL_RGB:
				return "GL_RGB";
			case GL11.GL_RGBA:
				return "GL_RGBA";
			default:
				return "0x" + Integer.toHexString(format);
		}
	}

	private static String typeName(int type) {
		switch (type) {
			case GL11.GL_BYTE:
				return "GL_BYTE";
			case GL11.GL_UNSIGNED_BYTE:
				return "GL_UNSIGNED_BYTE";
			case GL11.GL_SHORT:
				return "GL_SHORT";
			case GL11.GL_UNSIGNED_SHORT:
				return "GL_UNSIGNED_SHORT";
			case GL11.GL_INT:
				return "GL_INT";
			case GL11.GL_UNSIGNED_INT:
				return "GL_UNSIGNED_INT";
			case GL11.GL_FLOAT:
				return "GL_FLOAT";
			default:
				return "0x" + Integer.toHexString(type);
		}
	}
}
