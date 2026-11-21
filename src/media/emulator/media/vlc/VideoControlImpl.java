/*
Copyright (c) 2024 Arman Jussupgaliyev
*/
package emulator.media.vlc;

import javax.imageio.ImageIO;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.microedition.media.control.VideoControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

class VideoControlImpl implements VideoControl {

	private final VLCPlayerImpl player;

	VideoControlImpl(VLCPlayerImpl player) {
		this.player = player;
	}

	public int getDisplayHeight() {
		return player.height;
	}

	public int getDisplayWidth() {
		return player.width;
	}

	public int getDisplayX() {
		return player.displayX;
	}

	public int getDisplayY() {
		return player.displayY;
	}

	public void setDisplayFullScreen(boolean b) {
		player.fullscreen = b;
	}

	public void setDisplayLocation(int x, int y) {
		player.displayX = x;
		player.displayY = y;
	}

	public void setDisplaySize(int w, int h) {
		if (player.width == w && player.height == h) return;
		player.width = w;
		player.height = h;
		player.refreshVideoItem(true);
		player.notifyListeners(PlayerListener.SIZE_CHANGED, this);
	}

	public void setVisible(boolean b) {
		player.visible = b;
		player.refreshVideoItem(false);
	}

	public int getSourceHeight() {
		if (player.isCapture()) return player.bufferHeight;
		if (player.released || player.mediaPlayer == null) {
			throw new IllegalStateException();
		}
		try {
			if (player.mediaPlayer.video().videoDimension() == null) {
				player.mediaPlayer.media().parsing().parse();
				Thread.sleep(100L);
			}
			player.sourceHeight = player.mediaPlayer.video().videoDimension().height;
		} catch (Exception ignored) {}
		if (player.sourceHeight == 0)
			player.sourceHeight = player.bufferHeight;
		return player.sourceHeight;
	}

	public int getSourceWidth() {
		if (player.isCapture()) return player.bufferWidth;
		if (player.released || player.mediaPlayer == null) {
			throw new IllegalStateException();
		}
		try {
			if (player.mediaPlayer.video().videoDimension() == null) {
				player.mediaPlayer.media().parsing().parse();
				Thread.sleep(100L);
			}
			player.sourceWidth = player.mediaPlayer.video().videoDimension().width;
		} catch (Exception ignored) {}
		if (player.sourceWidth == 0)
			player.sourceWidth = player.bufferWidth;
		return player.sourceWidth;
	}

	public byte[] getSnapshot(String p0) throws MediaException {
		if (player.released || player.mediaPlayer == null)
			throw new IllegalStateException();
		BufferedImage frame = player.img;
		if (player.isCapture()) {
			if (player.getState() != Player.STARTED || frame == null) {
				throw new MediaException("No camera frame available yet");
			}
			return cameraSnapshot(frame, p0);
		}
		if (frame != null) {
			return VLCPlayerImpl.imgToBytes(frame);
		}
		try {
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			ImageIO.write(player.mediaPlayer.snapshots().get(), "JPEG", os);
			byte[] bytes = os.toByteArray();
			os.close();
			return bytes;
		} catch (IOException e) {
			e.printStackTrace();
			throw new MediaException(e);
		}
	}

	private static byte[] cameraSnapshot(BufferedImage frame, String options) throws MediaException {
		int width = -1;
		int height = -1;
		String format = "jpeg";
		try {
			if (options != null) {
				for (String option : options.split("&")) {
					if (option.startsWith("width=")) width = Integer.parseInt(option.substring(6));
					else if (option.startsWith("height=")) height = Integer.parseInt(option.substring(7));
					else if (option.startsWith("encoding=")) format = option.substring(9);
				}
			}
			if (width > 0 || height > 0) {
				if (width <= 0) width = Math.max(1, (int) ((long) frame.getWidth() * height / frame.getHeight()));
				if (height <= 0) height = Math.max(1, (int) ((long) frame.getHeight() * width / frame.getWidth()));
				BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
				Graphics2D graphics = resized.createGraphics();
				try {
					graphics.drawImage(frame, 0, 0, width, height, null);
				} finally {
					graphics.dispose();
				}
				frame = resized;
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(frame, format, output)) throw new MediaException("Unsupported snapshot encoding: " + format);
			return output.toByteArray();
		} catch (IOException | IllegalArgumentException e) {
			throw new MediaException(e);
		}
	}

	public Object initDisplayMode(int p0, Object p1) {
		if (p0 == 0) {
			player.isItem = true;
			return player.getItem();
		}
		player.isItem = false;
		player.canvas = p1;
		return null;
	}
}