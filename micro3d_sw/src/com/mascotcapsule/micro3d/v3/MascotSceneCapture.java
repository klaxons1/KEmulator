/*
 * MIT License
 * Copyright (c) 2026
 */

package com.mascotcapsule.micro3d.v3;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the last Mascot Capsule frame submitted through {@link Graphics3D}
 * so the glTF ripper can reconstruct the scene (figures, primitives, lights,
 * projection) instead of only dumping heap objects.
 */
final class MascotSceneCapture {

	static final Object LOCK = new Object();

	private static Frame current;
	private static Frame last;

	static final class ProjSnap {
		int mode; // Graphics3D.PROJ_PARALLEL / PROJ_PERSPECTIVE
		int near, far;
		int scaleX, scaleY;
		int centerX, centerY;
		int fbW, fbH;
		int layoutCmd;
		int angle, parallelW, parallelH, perspW, perspH;
	}

	static final class EffectSnap {
		boolean hasLight;
		int amb, dir;
		int lx, ly, lz;
		boolean toon;
		boolean transparency;
		int toonThreshold, toonLow, toonHigh;
		Texture sphere;
	}

	static final class FigureDraw {
		Figure figure;
		AffineTrans view;
		ProjSnap proj;
		EffectSnap effect;
		int pattern;
		int textureIndex;
		Texture[] textures;
		ActionTable actionTable;
		int actionIndex;
		int actionFrame;
	}

	static final class PrimDraw {
		int command;
		int numPrims;
		boolean quad;
		int[] verts;
		int[] normals;
		int[] uvs;
		int[] colors;
		Texture texture;
		AffineTrans view;
		ProjSnap proj;
		EffectSnap effect;
	}

	static final class Frame {
		int width, height;
		final List<FigureDraw> figures = new ArrayList<FigureDraw>();
		final List<PrimDraw> prims = new ArrayList<PrimDraw>();
	}

	static void begin(int width, int height) {
		synchronized (LOCK) {
			current = new Frame();
			current.width = width;
			current.height = height;
		}
	}

	static void commit() {
		synchronized (LOCK) {
			if (current != null) {
				last = current;
			}
		}
	}

	static Frame copyLast() {
		synchronized (LOCK) {
			if (last == null) {
				return null;
			}
			Frame copy = new Frame();
			copy.width = last.width;
			copy.height = last.height;
			copy.figures.addAll(last.figures);
			copy.prims.addAll(last.prims);
			return copy;
		}
	}

	static boolean hasCapturedScene() {
		synchronized (LOCK) {
			return last != null && (!last.figures.isEmpty() || !last.prims.isEmpty());
		}
	}

	static AffineTrans copyAffine(AffineTrans src) {
		return src == null ? null : new AffineTrans(src);
	}

	static void captureFigure(Graphics3D g3d, Figure figure, AffineTrans view,
							  FigureLayout layout, Effect3D effect) {
		if (figure == null || figure.vertices == null) {
			return;
		}
		FigureDraw draw = new FigureDraw();
		draw.figure = figure;
		draw.view = copyAffine(view);
		draw.proj = g3d.snapProj(layout);
		draw.effect = g3d.snapEffect(effect);
		draw.pattern = figure.selectedPattern;
		draw.textureIndex = figure.textureIndex;
		draw.textures = figure.textures;
		draw.actionTable = figure.actionTable;
		draw.actionFrame = figure.actionFrame;
		draw.actionIndex = -1;
		if (figure.actionTable != null && figure.activeAction != null) {
			ActionTable.Action[] acts = figure.actionTable.actions;
			for (int i = 0; i < acts.length; i++) {
				if (acts[i] == figure.activeAction) {
					draw.actionIndex = i;
					break;
				}
			}
		}
		synchronized (LOCK) {
			if (current != null) {
				current.figures.add(draw);
			}
		}
	}

	static void capturePrim(Graphics3D g3d, Texture texture, AffineTrans view,
							int command, int numPrims, boolean quad,
							int[] verts, int vtxOff, int[] normals, int nrmOff,
							int[] uvs, int uvOff, int[] colors, int colOff) {
		if (numPrims <= 0) {
			return;
		}
		int primType = command & 0xFF000000;
		int vtxPer;
		if (primType == Graphics3D.PRIMITVE_LINES) {
			vtxPer = 2;
		} else if (primType == Graphics3D.PRIMITVE_TRIANGLES) {
			vtxPer = 3;
		} else if (primType == Graphics3D.PRIMITVE_QUADS) {
			vtxPer = 4;
		} else {
			vtxPer = 1;
		}

		int vtxCount = numPrims * vtxPer;
		PrimDraw draw = new PrimDraw();
		draw.command = command;
		draw.numPrims = numPrims;
		draw.quad = quad;
		draw.texture = texture;
		draw.view = copyAffine(view);
		draw.proj = g3d.snapProj(null);
		draw.effect = g3d.snapEffect(null);
		draw.verts = copyRange(verts, vtxOff, vtxCount * 3);

		int normalType = command & 0x0300;
		if (normalType == Graphics3D.PDATA_NORMAL_PER_FACE) {
			draw.normals = copyRange(normals, nrmOff, numPrims * 3);
		} else if (normalType == Graphics3D.PDATA_NORMAL_PER_VERTEX) {
			draw.normals = copyRange(normals, nrmOff, vtxCount * 3);
		}

		if (primType == Graphics3D.PRIMITVE_POINT_SPRITES) {
			int sprMode = command & 0x3000;
			if (sprMode == Graphics3D.PDATA_POINT_SPRITE_PARAMS_PER_CMD) {
				draw.uvs = copyRange(uvs, uvOff, 8);
			} else if (sprMode != 0) {
				draw.uvs = copyRange(uvs, uvOff, numPrims * 8);
			}
		} else if ((command & Graphics3D.PDATA_TEXURE_COORD) != 0) {
			draw.uvs = copyRange(uvs, uvOff, vtxCount * 2);
		}

		int colorType = command & 0x0C00;
		if (colorType == Graphics3D.PDATA_COLOR_PER_COMMAND) {
			draw.colors = copyRange(colors, colOff, 1);
		} else if (colorType == Graphics3D.PDATA_COLOR_PER_FACE) {
			draw.colors = copyRange(colors, colOff, numPrims);
		}

		synchronized (LOCK) {
			if (current != null) {
				current.prims.add(draw);
			}
		}
	}

	private static int[] copyRange(int[] src, int off, int len) {
		if (src == null || len <= 0 || off < 0 || off >= src.length) {
			return null;
		}
		int avail = src.length - off;
		int n = len < avail ? len : avail;
		int[] dst = new int[n];
		System.arraycopy(src, off, dst, 0, n);
		return dst;
	}

	private MascotSceneCapture() {}
}
