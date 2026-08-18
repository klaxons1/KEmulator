/*
 * MIT License
 * Copyright (c) 2026
 */

package com.mascotcapsule.micro3d.v3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 (.glb) scene ripper for the MascotME software renderer.
 *
 * Exports the last submitted Graphics3D frame (figures + primitives) and,
 * if nothing was drawn yet, every live {@link Figure} still on the heap.
 *
 * Extracted data:
 *  - bind-pose vertices / normals, per-face UVs and palette colors
 *  - materials (blend, double-sided, color-key alpha, lighting / sphere-map flags)
 *  - BMP textures and sphere maps
 *  - rigid bone skins (one joint per vertex) and the current posture
 *  - ActionTable animations sampled as TRS tracks
 *  - immediate-mode points / lines / triangles / quads / point-sprites
 *  - last directional light (KHR_lights_punctual) and projection camera
 *
 * Coordinates stay in Mascot space (integer units, +Z forward). A camera node
 * is rotated 180 deg around Y so glTF viewers that look down -Z see the scene.
 */
public final class MascotGltfExporter {

	private static final float FP = 4096f;
	private static final float ANIM_FPS = 15f;
	private static final float[] IDENTITY = {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1
	};

	public static boolean hasScene() {
		if (MascotSceneCapture.hasCapturedScene()) {
			return true;
		}
		Figure[] live = Figure.liveFigures();
		return live != null && live.length > 0;
	}

	public static void export(File outFile) throws IOException {
		new MascotGltfExporter().doExport(outFile);
	}

	private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
	private final List<Object> gNodes = new ArrayList<Object>();
	private final List<Object> gMeshes = new ArrayList<Object>();
	private final List<Object> gAccessors = new ArrayList<Object>();
	private final List<Object> gBufferViews = new ArrayList<Object>();
	private final List<Object> gMaterials = new ArrayList<Object>();
	private final List<Object> gTextures = new ArrayList<Object>();
	private final List<Object> gImages = new ArrayList<Object>();
	private final List<Object> gSamplers = new ArrayList<Object>();
	private final List<Object> gLights = new ArrayList<Object>();
	private final List<Object> gCameras = new ArrayList<Object>();
	private final List<Object> gSkins = new ArrayList<Object>();
	private final List<Object> gAnimations = new ArrayList<Object>();

	private final IdentityHashMap<Texture, Integer> imageCache = new IdentityHashMap<Texture, Integer>();
	private final IdentityHashMap<Figure, int[]> boneOfVertexCache = new IdentityHashMap<Figure, int[]>();
	private final Map<Long, Integer> materialCache = new LinkedHashMap<Long, Integer>();
	private boolean usedUnlit;
	private boolean usedLights;

	private void doExport(File outFile) throws IOException {
		MascotSceneCapture.Frame frame = MascotSceneCapture.copyLast();
		List<MascotSceneCapture.FigureDraw> figures =
				frame != null ? frame.figures : Collections.<MascotSceneCapture.FigureDraw>emptyList();
		List<MascotSceneCapture.PrimDraw> prims =
				frame != null ? frame.prims : Collections.<MascotSceneCapture.PrimDraw>emptyList();

		if (figures.isEmpty() && prims.isEmpty()) {
			Figure[] live = Figure.liveFigures();
			if (live != null) {
				for (int i = 0; i < live.length; i++) {
					if (live[i] == null || live[i].vertices == null) continue;
					MascotSceneCapture.FigureDraw draw = new MascotSceneCapture.FigureDraw();
					draw.figure = live[i];
					draw.pattern = live[i].selectedPattern;
					draw.textureIndex = live[i].textureIndex;
					draw.textures = live[i].textures;
					draw.actionTable = live[i].actionTable;
					draw.actionFrame = live[i].actionFrame;
					draw.actionIndex = -1;
					if (live[i].actionTable != null && live[i].activeAction != null) {
						ActionTable.Action[] acts = live[i].actionTable.actions;
						for (int a = 0; a < acts.length; a++) {
							if (acts[a] == live[i].activeAction) {
								draw.actionIndex = a;
								break;
							}
						}
					}
					figures = new ArrayList<MascotSceneCapture.FigureDraw>(figures);
					figures.add(draw);
				}
			}
		}

		if (figures.isEmpty() && prims.isEmpty()) {
			throw new IOException("No Mascot Capsule scene to export");
		}

		int sampler = defaultSampler();
		List<Integer> roots = new ArrayList<Integer>();

		MascotSceneCapture.ProjSnap cameraProj = null;
		MascotSceneCapture.EffectSnap cameraFx = null;

		IdentityHashMap<Figure, Boolean> animated = new IdentityHashMap<Figure, Boolean>();

		for (int i = 0; i < figures.size(); i++) {
			MascotSceneCapture.FigureDraw draw = figures.get(i);
			if (draw.figure == null || draw.figure.vertices == null) continue;
			if (cameraProj == null && draw.proj != null) cameraProj = draw.proj;
			if (cameraFx == null && draw.effect != null) cameraFx = draw.effect;
			boolean writeAnim = !animated.containsKey(draw.figure);
			roots.add(exportFigureDraw(draw, sampler, i, writeAnim));
			if (writeAnim) animated.put(draw.figure, Boolean.TRUE);
		}

		for (int i = 0; i < prims.size(); i++) {
			MascotSceneCapture.PrimDraw draw = prims.get(i);
			if (cameraProj == null && draw.proj != null) cameraProj = draw.proj;
			if (cameraFx == null && draw.effect != null) cameraFx = draw.effect;
			int node = exportPrimDraw(draw, sampler, i);
			if (node >= 0) roots.add(Integer.valueOf(node));
		}

		if (cameraFx != null && cameraFx.hasLight) {
			int lightIdx = exportLight(cameraFx);
			if (lightIdx >= 0) {
				Map<String, Object> lnode = new LinkedHashMap<String, Object>();
				lnode.put("name", "MascotLight");
				lnode.put("matrix", floatList(lookAtLightMatrix(cameraFx)));
				Map<String, Object> ext = new LinkedHashMap<String, Object>();
				Map<String, Object> khr = new LinkedHashMap<String, Object>();
				khr.put("light", Integer.valueOf(lightIdx));
				ext.put("KHR_lights_punctual", khr);
				lnode.put("extensions", ext);
				gNodes.add(lnode);
				roots.add(Integer.valueOf(gNodes.size() - 1));
			}
		}

		if (cameraProj != null) {
			int cam = exportCamera(cameraProj);
			if (cam >= 0) {
				Map<String, Object> cnode = new LinkedHashMap<String, Object>();
				cnode.put("name", "MascotCamera");
				cnode.put("camera", Integer.valueOf(cam));
				// glTF cameras look down -Z; Mascot view space is +Z forward.
				cnode.put("matrix", floatList(new float[]{
						-1, 0, 0, 0,
						0, 1, 0, 0,
						0, 0, -1, 0,
						0, 0, 0, 1
				}));
				gNodes.add(cnode);
				roots.add(Integer.valueOf(gNodes.size() - 1));
			}
		}

		if (roots.isEmpty()) {
			throw new IOException("Mascot Capsule scene produced no nodes");
		}

		writeGlb(outFile, roots);
	}

	private int exportFigureDraw(MascotSceneCapture.FigureDraw draw, int sampler, int index, boolean writeAnim) {
		Figure figure = draw.figure;
		AffineTrans[] poseWorld = computePoseWorld(figure, draw);
		int meshIdx = exportFigureMesh(figure, draw, sampler, poseWorld);

		Map<String, Object> node = new LinkedHashMap<String, Object>();
		node.put("name", "Figure_" + index);
		node.put("mesh", Integer.valueOf(meshIdx));
		node.put("matrix", floatList(affineToGltf(draw.view)));
		node.put("extras", figureExtras(draw));

		List<Integer> children = new ArrayList<Integer>();
		int[] joints = exportBones(figure, draw, children);
		if (!children.isEmpty()) {
			node.put("children", children);
		}
		// Vertices are baked in the current posture so the ripped frame looks correct
		// in every viewer. The skeleton + ActionTable tracks are still exported so
		// the rig and animations can be inspected / reapplied.
		if (joints != null && joints.length > 0 && writeAnim && draw.actionTable != null) {
			exportAnimations(figure, draw.actionTable, joints, index);
		}

		gNodes.add(node);
		return gNodes.size() - 1;
	}

	private Map<String, Object> figureExtras(MascotSceneCapture.FigureDraw draw) {
		Map<String, Object> extras = new LinkedHashMap<String, Object>();
		extras.put("mascotPattern", Integer.valueOf(draw.pattern));
		extras.put("mascotNumPatterns", Integer.valueOf(draw.figure.getNumPattern()));
		extras.put("mascotTextureIndex", Integer.valueOf(draw.textureIndex));
		extras.put("mascotNumBones", Integer.valueOf(draw.figure.numBones));
		extras.put("mascotActionIndex", Integer.valueOf(draw.actionIndex));
		extras.put("mascotActionFrame", Integer.valueOf(draw.actionFrame));
		extras.put("mascotCoordinateSystem", "mascotCapsule +Z forward, 4096=1.0 rotation");
		return extras;
	}

	private int exportFigureMesh(Figure figure, MascotSceneCapture.FigureDraw draw, int sampler,
								 AffineTrans[] poseWorld) {
		int[] boneOf = boneOfVertex(figure);
		List<Float> positions = new ArrayList<Float>();
		List<Float> normals = new ArrayList<Float>();
		List<Float> uvs = new ArrayList<Float>();
		List<Float> colors = new ArrayList<Float>();
		List<Integer> joints0 = new ArrayList<Integer>();
		List<Float> weights0 = new ArrayList<Float>();

		List<Object> primitives = new ArrayList<Object>();
		int[][][] patterns = figure.patterns;
		int selected = draw.pattern;
		Texture[] texs = draw.textures != null ? draw.textures : figure.textures;
		int selectedTex = draw.textureIndex;

		if (patterns != null) {
			for (int p = 0; p < patterns.length; p++) {
				int patternBit = p == 0 ? 0 : 1 << p;
				if ((patternBit & selected) != patternBit) continue;
				int[][] patTexs = patterns[p];
				for (int t = 0; t < patTexs.length; t++) {
					int[] texData = patTexs[t];
					if (t > 0) {
						if (texs == null) break;
						int texId = t - 1;
						if (selectedTex != -1) {
							if (texId != 0) continue;
							texId = selectedTex;
						} else if (texId >= texs.length) {
							continue;
						}
						Texture tex = texs[texId];
						emitFigurePolys(figure, true, tex, texData[0], texData[2], texData[1], texData[3],
								boneOf, poseWorld, positions, normals, uvs, colors, joints0, weights0, primitives, sampler, draw);
					} else if (figure.colors != null) {
						emitFigurePolys(figure, false, null, texData[0], texData[2], texData[1], texData[3],
								boneOf, poseWorld, positions, normals, uvs, colors, joints0, weights0, primitives, sampler, draw);
					}
				}
			}
		}

		if (primitives.isEmpty()) {
			// Fallback: dump every polygon regardless of pattern.
			if (figure.numPolyT3 + figure.numPolyT4 > 0 && texs != null && texs.length > 0) {
				emitFigurePolys(figure, true, texs[selectedTex >= 0 ? selectedTex : 0],
						0, figure.numPolyT3, 0, figure.numPolyT4,
						boneOf, poseWorld, positions, normals, uvs, colors, joints0, weights0, primitives, sampler, draw);
			}
			if (figure.numPolyC3 + figure.numPolyC4 > 0 && figure.colors != null) {
				emitFigurePolys(figure, false, null,
						0, figure.numPolyC3, 0, figure.numPolyC4,
						boneOf, poseWorld, positions, normals, uvs, colors, joints0, weights0, primitives, sampler, draw);
			}
		}

		if (primitives.isEmpty()) {
			// Degenerate placeholder so the node still exists.
			positions.add(Float.valueOf(0f));
			positions.add(Float.valueOf(0f));
			positions.add(Float.valueOf(0f));
			int posAcc = writeAccessor(toFloatArray(positions), 3, true);
			Map<String, Object> attrs = new LinkedHashMap<String, Object>();
			attrs.put("POSITION", Integer.valueOf(posAcc));
			Map<String, Object> prim = new LinkedHashMap<String, Object>();
			prim.put("attributes", attrs);
			prim.put("mode", Integer.valueOf(0));
			primitives.add(prim);
		}

		Map<String, Object> mesh = new LinkedHashMap<String, Object>();
		mesh.put("name", "FigureMesh");
		mesh.put("primitives", primitives);
		gMeshes.add(mesh);
		return gMeshes.size() - 1;
	}

	private void emitFigurePolys(Figure figure, boolean textured, Texture tex,
								 int triStart, int triCount, int quadStart, int quadCount,
								 int[] boneOf, AffineTrans[] poseWorld,
								 List<Float> positions, List<Float> normals, List<Float> uvs, List<Float> colors,
								 List<Integer> joints0, List<Float> weights0,
								 List<Object> primitives, int sampler,
								 MascotSceneCapture.FigureDraw draw) {
		// Group by material flags + color so each glTF primitive is one material.
		Map<Long, List<Integer>> groups = new LinkedHashMap<Long, List<Integer>>();
		Map<Long, Integer> groupColor = new LinkedHashMap<Long, Integer>();
		Map<Long, Integer> groupFlags = new LinkedHashMap<Long, Integer>();

		if (textured) {
			collectTexTris(figure.polyT3, Figure.TRI_T_STRIDE, triStart, triCount, groups, groupFlags, groupColor);
			collectTexQuads(figure.polyT4, Figure.QUAD_T_STRIDE, quadStart, quadCount, groups, groupFlags, groupColor);
		} else {
			collectColorTris(figure.polyC3, figure.colors, Figure.TRI_C_STRIDE, triStart, triCount, groups, groupFlags, groupColor);
			collectColorQuads(figure.polyC4, figure.colors, Figure.QUAD_C_STRIDE, quadStart, quadCount, groups, groupFlags, groupColor);
		}

		for (Map.Entry<Long, List<Integer>> e : groups.entrySet()) {
			List<Integer> idxs = e.getValue();
			if (idxs.isEmpty()) continue;
			int base = positions.size() / 3;
			int[] triIndices = new int[idxs.size()];
			for (int i = 0; i < idxs.size(); i++) {
				int src = idxs.get(i).intValue();
				int v = src & 0xffff;
				int packedUv = (src >>> 16) & 0xffff;
				pushVertex(figure, v, packedUv, groupColor.get(e.getKey()).intValue(),
						textured, tex, boneOf, poseWorld, positions, normals, uvs, colors, joints0, weights0);
				triIndices[i] = base + i;
			}

			int posAcc = writeAccessor(toFloatArray(positions.subList(base * 3, positions.size())), 3, true);
			int nrmAcc = writeAccessor(toFloatArray(normals.subList(base * 3, normals.size())), 3, false);
			int uvAcc = textured ? writeAccessor(toFloatArray(uvs.subList(base * 2, uvs.size())), 2, false) : -1;
			int colAcc = writeAccessor(toFloatArray(colors.subList(base * 4, colors.size())), 4, false);

			Map<String, Object> attrs = new LinkedHashMap<String, Object>();
			attrs.put("POSITION", Integer.valueOf(posAcc));
			attrs.put("NORMAL", Integer.valueOf(nrmAcc));
			if (uvAcc >= 0) attrs.put("TEXCOORD_0", Integer.valueOf(uvAcc));
			attrs.put("COLOR_0", Integer.valueOf(colAcc));

			int flags = groupFlags.get(e.getKey()).intValue();
			int color = groupColor.get(e.getKey()).intValue();
			int mat = exportMaterial(tex, color, flags, textured, sampler, draw);

			Map<String, Object> prim = new LinkedHashMap<String, Object>();
			prim.put("attributes", attrs);
			prim.put("indices", Integer.valueOf(writeIndexAccessor(triIndices)));
			prim.put("mode", Integer.valueOf(4));
			if (mat >= 0) prim.put("material", Integer.valueOf(mat));
			Map<String, Object> extras = new LinkedHashMap<String, Object>();
			extras.put("mascotMaterialFlags", Integer.valueOf(flags));
			prim.put("extras", extras);
			primitives.add(prim);

			// Drop the just-emitted vertex tail from the shared lists? No - we sliced subList for accessors
			// but left data in the lists. That's fine: next primitive uses a new base.
			// To keep memory reasonable, we can clear after writing accessors.
			truncate(positions, base * 3);
			truncate(normals, base * 3);
			truncate(uvs, base * 2);
			truncate(colors, base * 4);
			truncate(joints0, base * 4);
			truncate(weights0, base * 4);
		}
	}

	private static void truncate(List<?> list, int size) {
		while (list.size() > size) list.remove(list.size() - 1);
	}

	private void collectTexTris(short[] poly, int stride, int start, int count,
								Map<Long, List<Integer>> groups, Map<Long, Integer> flags, Map<Long, Integer> colors) {
		if (poly == null || count <= 0) return;
		int offset = start * stride;
		int end = offset + count * stride;
		for (; offset < end; offset += stride) {
			int mat = poly[offset] & Figure.MAT_MASK;
			long key = (((long) mat) << 32) ^ 0xFFFFFFFFL;
			List<Integer> list = groups.get(Long.valueOf(key));
			if (list == null) {
				list = new ArrayList<Integer>();
				groups.put(Long.valueOf(key), list);
				flags.put(Long.valueOf(key), Integer.valueOf(mat));
				colors.put(Long.valueOf(key), Integer.valueOf(0xFFFFFFFF));
			}
			int v0 = poly[offset + 1] & 0xffff;
			int v1 = poly[offset + 2] & 0xffff;
			int v2 = poly[offset + 3] & 0xffff;
			list.add(Integer.valueOf(v0 | ((poly[offset + 4] & 0xffff) << 16)));
			list.add(Integer.valueOf(v1 | ((poly[offset + 5] & 0xffff) << 16)));
			list.add(Integer.valueOf(v2 | ((poly[offset + 6] & 0xffff) << 16)));
		}
	}

	private void collectTexQuads(short[] poly, int stride, int start, int count,
								 Map<Long, List<Integer>> groups, Map<Long, Integer> flags, Map<Long, Integer> colors) {
		if (poly == null || count <= 0) return;
		int offset = start * stride;
		int end = offset + count * stride;
		for (; offset < end; offset += stride) {
			int mat = poly[offset] & Figure.MAT_MASK;
			long key = (((long) mat) << 32) ^ 0xFFFFFFFEL;
			List<Integer> list = groups.get(Long.valueOf(key));
			if (list == null) {
				list = new ArrayList<Integer>();
				groups.put(Long.valueOf(key), list);
				flags.put(Long.valueOf(key), Integer.valueOf(mat));
				colors.put(Long.valueOf(key), Integer.valueOf(0xFFFFFFFF));
			}
			int v0 = poly[offset + 1] & 0xffff;
			int v1 = poly[offset + 2] & 0xffff;
			int v2 = poly[offset + 3] & 0xffff;
			int v3 = poly[offset + 4] & 0xffff;
			int uv0 = poly[offset + 5] & 0xffff;
			int uv1 = poly[offset + 6] & 0xffff;
			int uv2 = poly[offset + 7] & 0xffff;
			int uv3 = poly[offset + 8] & 0xffff;
			// ABC + CBD, matching the software rasterizer
			list.add(Integer.valueOf(v0 | (uv0 << 16)));
			list.add(Integer.valueOf(v1 | (uv1 << 16)));
			list.add(Integer.valueOf(v2 | (uv2 << 16)));
			list.add(Integer.valueOf(v2 | (uv2 << 16)));
			list.add(Integer.valueOf(v1 | (uv1 << 16)));
			list.add(Integer.valueOf(v3 | (uv3 << 16)));
		}
	}

	private void collectColorTris(short[] poly, int[] palette, int stride, int start, int count,
								  Map<Long, List<Integer>> groups, Map<Long, Integer> flags, Map<Long, Integer> colors) {
		if (poly == null || count <= 0) return;
		int offset = start * stride;
		int end = offset + count * stride;
		for (; offset < end; offset += stride) {
			int mat = poly[offset] & Figure.MAT_MASK;
			int ci = poly[offset + 1] & 0xff;
			int argb = (palette != null && ci < palette.length) ? palette[ci] : 0xFFFFFFFF;
			long key = (((long) mat) << 32) | (argb & 0xffffffffL);
			List<Integer> list = groups.get(Long.valueOf(key));
			if (list == null) {
				list = new ArrayList<Integer>();
				groups.put(Long.valueOf(key), list);
				flags.put(Long.valueOf(key), Integer.valueOf(mat));
				colors.put(Long.valueOf(key), Integer.valueOf(argb));
			}
			list.add(Integer.valueOf(poly[offset + 2] & 0xffff));
			list.add(Integer.valueOf(poly[offset + 3] & 0xffff));
			list.add(Integer.valueOf(poly[offset + 4] & 0xffff));
		}
	}

	private void collectColorQuads(short[] poly, int[] palette, int stride, int start, int count,
								   Map<Long, List<Integer>> groups, Map<Long, Integer> flags, Map<Long, Integer> colors) {
		if (poly == null || count <= 0) return;
		int offset = start * stride;
		int end = offset + count * stride;
		for (; offset < end; offset += stride) {
			int mat = poly[offset] & Figure.MAT_MASK;
			int ci = poly[offset + 1] & 0xff;
			int argb = (palette != null && ci < palette.length) ? palette[ci] : 0xFFFFFFFF;
			long key = (((long) mat) << 32) | (argb & 0xffffffffL);
			List<Integer> list = groups.get(Long.valueOf(key));
			if (list == null) {
				list = new ArrayList<Integer>();
				groups.put(Long.valueOf(key), list);
				flags.put(Long.valueOf(key), Integer.valueOf(mat));
				colors.put(Long.valueOf(key), Integer.valueOf(argb));
			}
			int v0 = poly[offset + 2] & 0xffff;
			int v1 = poly[offset + 3] & 0xffff;
			int v2 = poly[offset + 4] & 0xffff;
			int v3 = poly[offset + 5] & 0xffff;
			list.add(Integer.valueOf(v0));
			list.add(Integer.valueOf(v1));
			list.add(Integer.valueOf(v2));
			list.add(Integer.valueOf(v2));
			list.add(Integer.valueOf(v1));
			list.add(Integer.valueOf(v3));
		}
	}

	private void pushVertex(Figure figure, int v, int packedUv, int argb, boolean textured, Texture tex,
							int[] boneOf, AffineTrans[] poseWorld,
							List<Float> positions, List<Float> normals, List<Float> uvs, List<Float> colors,
							List<Integer> joints0, List<Float> weights0) {
		int vi = v * 3;
		short[] vtx = figure.vertices;
		int px = vtx[vi];
		int py = vtx[vi + 1];
		int pz = vtx[vi + 2];
		int bone = (boneOf != null && v < boneOf.length) ? boneOf[v] : 0;
		if (poseWorld != null && bone < poseWorld.length && poseWorld[bone] != null) {
			AffineTrans t = poseWorld[bone];
			int rx = ((t.m00 * px + t.m01 * py + t.m02 * pz + 2048) >> 12) + t.m03;
			int ry = ((t.m10 * px + t.m11 * py + t.m12 * pz + 2048) >> 12) + t.m13;
			int rz = ((t.m20 * px + t.m21 * py + t.m22 * pz + 2048) >> 12) + t.m23;
			px = rx;
			py = ry;
			pz = rz;
		}
		positions.add(Float.valueOf(px));
		positions.add(Float.valueOf(py));
		positions.add(Float.valueOf(pz));

		if (figure.normals != null && vi + 2 < figure.normals.length) {
			int nx = figure.normals[vi];
			int ny = figure.normals[vi + 1];
			int nz = figure.normals[vi + 2];
			if (poseWorld != null && bone < poseWorld.length && poseWorld[bone] != null) {
				AffineTrans t = poseWorld[bone];
				int rx = (t.m00 * nx + t.m01 * ny + t.m02 * nz + 2048) >> 12;
				int ry = (t.m10 * nx + t.m11 * ny + t.m12 * nz + 2048) >> 12;
				int rz = (t.m20 * nx + t.m21 * ny + t.m22 * nz + 2048) >> 12;
				nx = rx;
				ny = ry;
				nz = rz;
			}
			float len = (float) Math.sqrt((float) nx * nx + (float) ny * ny + (float) nz * nz);
			if (len < 1e-3f) len = 1f;
			normals.add(Float.valueOf(nx / len));
			normals.add(Float.valueOf(ny / len));
			normals.add(Float.valueOf(nz / len));
		} else {
			normals.add(Float.valueOf(0f));
			normals.add(Float.valueOf(0f));
			normals.add(Float.valueOf(1f));
		}

		if (textured) {
			int au = (packedUv >> 8) & 0xff;
			int av = packedUv & 0xff;
			float tw = tex != null && tex.paddedWidth > 0 ? tex.paddedWidth : 256f;
			float th = tex != null && tex.paddedHeight > 0 ? tex.paddedHeight : 256f;
			uvs.add(Float.valueOf(au / tw));
			uvs.add(Float.valueOf(av / th));
		}

		colors.add(Float.valueOf(((argb >> 16) & 0xff) / 255f));
		colors.add(Float.valueOf(((argb >> 8) & 0xff) / 255f));
		colors.add(Float.valueOf((argb & 0xff) / 255f));
		colors.add(Float.valueOf(((argb >>> 24) & 0xff) / 255f));

		joints0.add(Integer.valueOf(bone));
		joints0.add(Integer.valueOf(0));
		joints0.add(Integer.valueOf(0));
		joints0.add(Integer.valueOf(0));
		weights0.add(Float.valueOf(1f));
		weights0.add(Float.valueOf(0f));
		weights0.add(Float.valueOf(0f));
		weights0.add(Float.valueOf(0f));
	}

	private int[] boneOfVertex(Figure figure) {
		int[] cached = boneOfVertexCache.get(figure);
		if (cached != null) return cached;
		int[] map = new int[figure.numVertices];
		if (figure.bones != null) {
			for (int b = 0; b < figure.bones.length; b++) {
				Figure.Bone bone = figure.bones[b];
				int end = bone.startVertex + bone.numVertices;
				for (int v = bone.startVertex; v < end && v < map.length; v++) {
					map[v] = b;
				}
			}
		}
		boneOfVertexCache.put(figure, map);
		return map;
	}

	private int[] exportBones(Figure figure, MascotSceneCapture.FigureDraw draw, List<Integer> rootChildren) {
		if (figure.bones == null || figure.bones.length == 0) return null;
		int n = figure.bones.length;
		int[] nodeIdx = new int[n];
		List<List<Integer>> kids = new ArrayList<List<Integer>>(n);
		for (int i = 0; i < n; i++) kids.add(new ArrayList<Integer>());

		ActionTable.Action action = null;
		if (draw.actionTable != null && draw.actionIndex >= 0
				&& draw.actionIndex < draw.actionTable.actions.length) {
			action = draw.actionTable.actions[draw.actionIndex];
		}

		AffineTrans tmp = new AffineTrans();
		for (int i = 0; i < n; i++) {
			Figure.Bone bone = figure.bones[i];
			localBone(i, bone, action, draw.actionFrame, tmp);
			Map<String, Object> bnode = new LinkedHashMap<String, Object>();
			bnode.put("name", "Bone_" + i);
			bnode.put("matrix", floatList(affineToGltf(tmp)));
			gNodes.add(bnode);
			nodeIdx[i] = gNodes.size() - 1;
			if (bone.parentIndex >= 0 && bone.parentIndex < n) {
				kids.get(bone.parentIndex).add(Integer.valueOf(nodeIdx[i]));
			} else {
				rootChildren.add(Integer.valueOf(nodeIdx[i]));
			}
		}
		for (int i = 0; i < n; i++) {
			if (!kids.get(i).isEmpty()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> bnode = (Map<String, Object>) gNodes.get(nodeIdx[i]);
				bnode.put("children", kids.get(i));
			}
		}
		return nodeIdx;
	}

	private static void localBone(int boneIdx, Figure.Bone bone, ActionTable.Action action, int frame, AffineTrans out) {
		out.setIdentity();
		if (action != null) {
			action.updateBoneAnim(boneIdx, frame, bone.localTrans, out);
			return;
		}
		if (bone.localTrans != null) out.set(bone.localTrans);
	}

	private AffineTrans[] computePoseWorld(Figure figure, MascotSceneCapture.FigureDraw draw) {
		if (figure.bones == null || figure.bones.length == 0) {
			return null;
		}
		int n = figure.bones.length;
		AffineTrans[] world = new AffineTrans[n];
		AffineTrans local = new AffineTrans();
		ActionTable.Action action = null;
		if (draw.actionTable != null && draw.actionIndex >= 0
				&& draw.actionIndex < draw.actionTable.actions.length) {
			action = draw.actionTable.actions[draw.actionIndex];
		}
		for (int i = 0; i < n; i++) {
			world[i] = new AffineTrans();
			localBone(i, figure.bones[i], action, draw.actionFrame, local);
			if (figure.bones[i].parentIndex >= 0 && figure.bones[i].parentIndex < n) {
				world[i].mulA2(world[figure.bones[i].parentIndex], local);
			} else {
				world[i].set(local);
			}
		}
		return world;
	}

	private int exportSkin(Figure figure, int[] jointNodes) {
		int n = figure.bones.length;
		ActionTable.Action saved = figure.activeAction;
		int savedFrame = figure.actionFrame;
		figure.activeAction = null;

		AffineTrans[] bindWorld = new AffineTrans[n];
		for (int i = 0; i < n; i++) bindWorld[i] = new AffineTrans();
		for (int i = 0; i < n; i++) {
			figure.updateBoneTrans(i, null, bindWorld);
		}
		figure.activeAction = saved;
		figure.actionFrame = savedFrame;

		float[] ibm = new float[n * 16];
		for (int i = 0; i < n; i++) {
			float[] m = affineToGltf(bindWorld[i]);
			float[] inv = new float[16];
			if (!invertMatrix(m, inv)) {
				System.arraycopy(IDENTITY, 0, inv, 0, 16);
			}
			System.arraycopy(inv, 0, ibm, i * 16, 16);
		}

		ByteBuffer bb = ByteBuffer.allocate(ibm.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < ibm.length; i++) bb.putFloat(ibm[i]);
		int bv = addBufferView(bb.array(), 0);

		Map<String, Object> acc = new LinkedHashMap<String, Object>();
		acc.put("bufferView", Integer.valueOf(bv));
		acc.put("componentType", Integer.valueOf(5126));
		acc.put("count", Integer.valueOf(n));
		acc.put("type", "MAT4");
		gAccessors.add(acc);
		int ibmAcc = gAccessors.size() - 1;

		List<Integer> joints = new ArrayList<Integer>(n);
		for (int i = 0; i < n; i++) joints.add(Integer.valueOf(jointNodes[i]));

		Map<String, Object> skin = new LinkedHashMap<String, Object>();
		skin.put("joints", joints);
		skin.put("inverseBindMatrices", Integer.valueOf(ibmAcc));
		gSkins.add(skin);
		return gSkins.size() - 1;
	}

	private void exportAnimations(Figure figure, ActionTable table, int[] jointNodes, int figureIndex) {
		if (table == null || table.actions == null) return;
		for (int a = 0; a < table.actions.length; a++) {
			ActionTable.Action act = table.actions[a];
			if (act == null || act.keyFrames <= 0) continue;
			int frames = act.keyFrames;
			float[] times = new float[frames];
			for (int f = 0; f < frames; f++) times[f] = f / ANIM_FPS;
			int timeAcc = writeAccessor(times, 1, true);

			List<Object> channels = new ArrayList<Object>();
			List<Object> samplers = new ArrayList<Object>();
			AffineTrans local = new AffineTrans();

			int boneCount = Math.min(figure.bones.length, act.boneAnims != null ? act.boneAnims.length : 0);
			for (int b = 0; b < boneCount; b++) {
				float[] t = new float[frames * 3];
				float[] r = new float[frames * 4];
				float[] s = new float[frames * 3];
				for (int f = 0; f < frames; f++) {
					act.updateBoneAnim(b, f << 16, figure.bones[b].localTrans, local);
					float[] tr = new float[3], q = new float[4], sc = new float[3];
					decomposeAffine(local, tr, q, sc);
					t[f * 3] = tr[0];
					t[f * 3 + 1] = tr[1];
					t[f * 3 + 2] = tr[2];
					r[f * 4] = q[0];
					r[f * 4 + 1] = q[1];
					r[f * 4 + 2] = q[2];
					r[f * 4 + 3] = q[3];
					s[f * 3] = sc[0];
					s[f * 3 + 1] = sc[1];
					s[f * 3 + 2] = sc[2];
				}
				addTrsChannel(channels, samplers, timeAcc, t, r, s, jointNodes[b]);
			}

			if (channels.isEmpty()) continue;
			Map<String, Object> anim = new LinkedHashMap<String, Object>();
			anim.put("name", "Figure_" + figureIndex + "_action_" + a);
			anim.put("samplers", samplers);
			anim.put("channels", channels);
			gAnimations.add(anim);
		}
	}

	private void addTrsChannel(List<Object> channels, List<Object> samplers,
							   int timeAcc, float[] t, float[] r, float[] s, int node) {
		int tAcc = writeAccessor(t, 3, false);
		int rAcc = writeAccessor(r, 4, false);
		int sAcc = writeAccessor(s, 3, false);
		addChannel(channels, samplers, timeAcc, tAcc, node, "translation");
		addChannel(channels, samplers, timeAcc, rAcc, node, "rotation");
		addChannel(channels, samplers, timeAcc, sAcc, node, "scale");
	}

	private void addChannel(List<Object> channels, List<Object> samplers,
							int timeAcc, int outAcc, int node, String path) {
		Map<String, Object> samp = new LinkedHashMap<String, Object>();
		samp.put("input", Integer.valueOf(timeAcc));
		samp.put("output", Integer.valueOf(outAcc));
		samp.put("interpolation", "LINEAR");
		samplers.add(samp);
		int si = samplers.size() - 1;
		Map<String, Object> target = new LinkedHashMap<String, Object>();
		target.put("node", Integer.valueOf(node));
		target.put("path", path);
		Map<String, Object> ch = new LinkedHashMap<String, Object>();
		ch.put("sampler", Integer.valueOf(si));
		ch.put("target", target);
		channels.add(ch);
	}

	private int exportPrimDraw(MascotSceneCapture.PrimDraw draw, int sampler, int index) {
		int type = draw.command & 0xFF000000;
		int mesh;
		if (type == Graphics3D.PRIMITVE_POINTS) {
			mesh = exportPointMesh(draw, sampler);
		} else if (type == Graphics3D.PRIMITVE_LINES) {
			mesh = exportLineMesh(draw, sampler);
		} else if (type == Graphics3D.PRIMITVE_POINT_SPRITES) {
			mesh = exportSpriteMesh(draw, sampler);
		} else if (type == Graphics3D.PRIMITVE_TRIANGLES || type == Graphics3D.PRIMITVE_QUADS) {
			mesh = exportPolyMesh(draw, sampler);
		} else {
			return -1;
		}
		if (mesh < 0) return -1;

		Map<String, Object> node = new LinkedHashMap<String, Object>();
		String name = type == Graphics3D.PRIMITVE_POINTS ? "Points_"
				: type == Graphics3D.PRIMITVE_LINES ? "Lines_"
				: type == Graphics3D.PRIMITVE_POINT_SPRITES ? "Sprites_"
				: draw.quad ? "Quads_" : "Triangles_";
		node.put("name", name + index);
		node.put("mesh", Integer.valueOf(mesh));
		node.put("matrix", floatList(affineToGltf(draw.view)));
		Map<String, Object> extras = new LinkedHashMap<String, Object>();
		extras.put("mascotCommand", Integer.valueOf(draw.command));
		extras.put("mascotNumPrimitives", Integer.valueOf(draw.numPrims));
		node.put("extras", extras);
		gNodes.add(node);
		return gNodes.size() - 1;
	}

	private int exportPointMesh(MascotSceneCapture.PrimDraw draw, int sampler) {
		if (draw.verts == null || draw.verts.length < 3) return -1;
		int count = draw.verts.length / 3;
		float[] pos = new float[count * 3];
		float[] col = new float[count * 4];
		int color = 0xFFFFFFFF;
		if (draw.colors != null && draw.colors.length > 0) color = 0xFF000000 | draw.colors[0];
		for (int i = 0; i < count; i++) {
			pos[i * 3] = draw.verts[i * 3];
			pos[i * 3 + 1] = draw.verts[i * 3 + 1];
			pos[i * 3 + 2] = draw.verts[i * 3 + 2];
			int c = color;
			if (draw.colors != null && draw.colors.length == count) c = 0xFF000000 | draw.colors[i];
			col[i * 4] = ((c >> 16) & 0xff) / 255f;
			col[i * 4 + 1] = ((c >> 8) & 0xff) / 255f;
			col[i * 4 + 2] = (c & 0xff) / 255f;
			col[i * 4 + 3] = 1f;
		}
		Map<String, Object> attrs = new LinkedHashMap<String, Object>();
		attrs.put("POSITION", Integer.valueOf(writeAccessor(pos, 3, true)));
		attrs.put("COLOR_0", Integer.valueOf(writeAccessor(col, 4, false)));
		Map<String, Object> prim = new LinkedHashMap<String, Object>();
		prim.put("attributes", attrs);
		prim.put("mode", Integer.valueOf(0));
		int mat = exportMaterial(null, color, 0, false, sampler, null);
		if (mat >= 0) prim.put("material", Integer.valueOf(mat));
		Map<String, Object> mesh = new LinkedHashMap<String, Object>();
		mesh.put("primitives", Collections.singletonList(prim));
		gMeshes.add(mesh);
		return gMeshes.size() - 1;
	}

	private int exportLineMesh(MascotSceneCapture.PrimDraw draw, int sampler) {
		if (draw.verts == null || draw.verts.length < 6) return -1;
		int count = draw.verts.length / 3;
		float[] pos = new float[count * 3];
		for (int i = 0; i < count; i++) {
			pos[i * 3] = draw.verts[i * 3];
			pos[i * 3 + 1] = draw.verts[i * 3 + 1];
			pos[i * 3 + 2] = draw.verts[i * 3 + 2];
		}
		int color = 0xFFFFFFFF;
		if (draw.colors != null && draw.colors.length > 0) color = 0xFF000000 | draw.colors[0];
		Map<String, Object> attrs = new LinkedHashMap<String, Object>();
		attrs.put("POSITION", Integer.valueOf(writeAccessor(pos, 3, true)));
		Map<String, Object> prim = new LinkedHashMap<String, Object>();
		prim.put("attributes", attrs);
		prim.put("mode", Integer.valueOf(1));
		int mat = exportMaterial(null, color, 0, false, sampler, null);
		if (mat >= 0) prim.put("material", Integer.valueOf(mat));
		Map<String, Object> mesh = new LinkedHashMap<String, Object>();
		mesh.put("primitives", Collections.singletonList(prim));
		gMeshes.add(mesh);
		return gMeshes.size() - 1;
	}

	private int exportPolyMesh(MascotSceneCapture.PrimDraw draw, int sampler) {
		if (draw.verts == null) return -1;
		int vtxPer = draw.quad ? 4 : 3;
		int prims = draw.numPrims;
		List<Float> pos = new ArrayList<Float>();
		List<Float> nrm = new ArrayList<Float>();
		List<Float> uv = new ArrayList<Float>();
		List<Float> col = new ArrayList<Float>();
		List<Integer> idx = new ArrayList<Integer>();
		boolean hasUv = draw.uvs != null && draw.texture != null;
		int cmdColor = 0xFFFFFFFF;
		if (draw.colors != null && draw.colors.length == 1) cmdColor = 0xFF000000 | draw.colors[0];

		for (int p = 0; p < prims; p++) {
			int faceColor = cmdColor;
			if (draw.colors != null && draw.colors.length == prims) faceColor = 0xFF000000 | draw.colors[p];
			int[] order;
			if (draw.quad) {
				order = new int[]{0, 1, 3, 1, 2, 3};
			} else {
				order = new int[]{0, 1, 2};
			}
			for (int o = 0; o < order.length; o++) {
				int vi = p * vtxPer + order[o];
				int base = pos.size() / 3;
				if (vi * 3 + 2 >= draw.verts.length) continue;
				pos.add(Float.valueOf(draw.verts[vi * 3]));
				pos.add(Float.valueOf(draw.verts[vi * 3 + 1]));
				pos.add(Float.valueOf(draw.verts[vi * 3 + 2]));
				if (draw.normals != null) {
					int ni;
					if (draw.normals.length >= prims * vtxPer * 3) ni = vi * 3;
					else ni = p * 3;
					if (ni + 2 < draw.normals.length) {
						nrm.add(Float.valueOf(draw.normals[ni] / FP));
						nrm.add(Float.valueOf(draw.normals[ni + 1] / FP));
						nrm.add(Float.valueOf(draw.normals[ni + 2] / FP));
					} else {
						nrm.add(Float.valueOf(0f));
						nrm.add(Float.valueOf(0f));
						nrm.add(Float.valueOf(1f));
					}
				} else {
					nrm.add(Float.valueOf(0f));
					nrm.add(Float.valueOf(0f));
					nrm.add(Float.valueOf(1f));
				}
				if (hasUv && vi * 2 + 1 < draw.uvs.length) {
					float tw = draw.texture.paddedWidth > 0 ? draw.texture.paddedWidth : 256f;
					float th = draw.texture.paddedHeight > 0 ? draw.texture.paddedHeight : 256f;
					uv.add(Float.valueOf(draw.uvs[vi * 2] / tw));
					uv.add(Float.valueOf(draw.uvs[vi * 2 + 1] / th));
				}
				col.add(Float.valueOf(((faceColor >> 16) & 0xff) / 255f));
				col.add(Float.valueOf(((faceColor >> 8) & 0xff) / 255f));
				col.add(Float.valueOf((faceColor & 0xff) / 255f));
				col.add(Float.valueOf(1f));
				idx.add(Integer.valueOf(base));
			}
		}
		if (pos.isEmpty()) return -1;

		Map<String, Object> attrs = new LinkedHashMap<String, Object>();
		attrs.put("POSITION", Integer.valueOf(writeAccessor(toFloatArray(pos), 3, true)));
		attrs.put("NORMAL", Integer.valueOf(writeAccessor(toFloatArray(nrm), 3, false)));
		if (hasUv && !uv.isEmpty()) attrs.put("TEXCOORD_0", Integer.valueOf(writeAccessor(toFloatArray(uv), 2, false)));
		attrs.put("COLOR_0", Integer.valueOf(writeAccessor(toFloatArray(col), 4, false)));

		int[] indices = new int[idx.size()];
		for (int i = 0; i < idx.size(); i++) indices[i] = idx.get(i).intValue();

		int flags = Figure.MAT_DOUBLE_FACE;
		if ((draw.command & Graphics3D.PATTR_COLORKEY) != 0) flags |= Figure.MAT_COLORKEY;
		int blend = (draw.command & 0x60) >> 5;
		flags |= blend << 1;
		if ((draw.command & Graphics3D.PATTR_LIGHTING) != 0) flags |= Figure.MAT_LIGHTING;
		if ((draw.command & Graphics3D.PATTR_SPHERE_MAP) != 0) flags |= Figure.MAT_SPECULAR;

		int mat = exportMaterial(hasUv ? draw.texture : null, cmdColor, flags, hasUv, sampler, null);
		Map<String, Object> prim = new LinkedHashMap<String, Object>();
		prim.put("attributes", attrs);
		prim.put("indices", Integer.valueOf(writeIndexAccessor(indices)));
		prim.put("mode", Integer.valueOf(4));
		if (mat >= 0) prim.put("material", Integer.valueOf(mat));
		Map<String, Object> mesh = new LinkedHashMap<String, Object>();
		mesh.put("primitives", Collections.singletonList(prim));
		gMeshes.add(mesh);
		return gMeshes.size() - 1;
	}

	private int exportSpriteMesh(MascotSceneCapture.PrimDraw draw, int sampler) {
		if (draw.verts == null || draw.uvs == null || draw.texture == null) return -1;
		int n = draw.numPrims;
		boolean perCmd = (draw.command & 0x3000) == Graphics3D.PDATA_POINT_SPRITE_PARAMS_PER_CMD;
		List<Float> pos = new ArrayList<Float>();
		List<Float> uv = new ArrayList<Float>();
		List<Integer> idx = new ArrayList<Integer>();
		float tw = draw.texture.paddedWidth > 0 ? draw.texture.paddedWidth : 256f;
		float th = draw.texture.paddedHeight > 0 ? draw.texture.paddedHeight : 256f;

		for (int i = 0; i < n; i++) {
			if (i * 3 + 2 >= draw.verts.length) break;
			float x = draw.verts[i * 3];
			float y = draw.verts[i * 3 + 1];
			float z = draw.verts[i * 3 + 2];
			int po = perCmd ? 0 : i * 8;
			if (po + 7 >= draw.uvs.length) break;
			float w = draw.uvs[po] * 0.5f;
			float h = draw.uvs[po + 1] * 0.5f;
			float u0 = draw.uvs[po + 3] / tw;
			float v0 = draw.uvs[po + 4] / th;
			float u1 = draw.uvs[po + 5] / tw;
			float v1 = draw.uvs[po + 6] / th;
			int base = pos.size() / 3;
			// Camera-facing quad in XY (Mascot sprites face the viewer).
			push3(pos, x - w, y - h, z);
			push3(pos, x + w, y - h, z);
			push3(pos, x + w, y + h, z);
			push3(pos, x - w, y + h, z);
			uv.add(Float.valueOf(u0)); uv.add(Float.valueOf(v0));
			uv.add(Float.valueOf(u1)); uv.add(Float.valueOf(v0));
			uv.add(Float.valueOf(u1)); uv.add(Float.valueOf(v1));
			uv.add(Float.valueOf(u0)); uv.add(Float.valueOf(v1));
			idx.add(Integer.valueOf(base));
			idx.add(Integer.valueOf(base + 1));
			idx.add(Integer.valueOf(base + 2));
			idx.add(Integer.valueOf(base));
			idx.add(Integer.valueOf(base + 2));
			idx.add(Integer.valueOf(base + 3));
		}
		if (pos.isEmpty()) return -1;

		int flags = Figure.MAT_DOUBLE_FACE;
		if ((draw.command & Graphics3D.PATTR_COLORKEY) != 0) flags |= Figure.MAT_COLORKEY;
		int blend = (draw.command & 0x60) >> 5;
		flags |= blend << 1;

		Map<String, Object> attrs = new LinkedHashMap<String, Object>();
		attrs.put("POSITION", Integer.valueOf(writeAccessor(toFloatArray(pos), 3, true)));
		attrs.put("TEXCOORD_0", Integer.valueOf(writeAccessor(toFloatArray(uv), 2, false)));
		int[] indices = new int[idx.size()];
		for (int i = 0; i < idx.size(); i++) indices[i] = idx.get(i).intValue();
		int mat = exportMaterial(draw.texture, 0xFFFFFFFF, flags, true, sampler, null);
		Map<String, Object> prim = new LinkedHashMap<String, Object>();
		prim.put("attributes", attrs);
		prim.put("indices", Integer.valueOf(writeIndexAccessor(indices)));
		prim.put("mode", Integer.valueOf(4));
		if (mat >= 0) prim.put("material", Integer.valueOf(mat));
		Map<String, Object> mesh = new LinkedHashMap<String, Object>();
		mesh.put("primitives", Collections.singletonList(prim));
		gMeshes.add(mesh);
		return gMeshes.size() - 1;
	}

	private static void push3(List<Float> list, float x, float y, float z) {
		list.add(Float.valueOf(x));
		list.add(Float.valueOf(y));
		list.add(Float.valueOf(z));
	}

	private int exportMaterial(Texture tex, int argb, int flags, boolean textured, int sampler,
							   MascotSceneCapture.FigureDraw draw) {
		boolean colorKey = (flags & Figure.MAT_COLORKEY) != 0;
		boolean doubleSided = (flags & Figure.MAT_DOUBLE_FACE) != 0;
		boolean lighting = (flags & Figure.MAT_LIGHTING) != 0;
		int blend = flags & Figure.MAT_BLEND_MASK;
		long key = ((long) System.identityHashCode(tex) << 32)
				^ (argb & 0xffffffffL)
				^ (((long) flags) << 8)
				^ (textured ? 1L : 0L);
		Integer cached = materialCache.get(Long.valueOf(key));
		if (cached != null) return cached.intValue();

		Map<String, Object> mat = new LinkedHashMap<String, Object>();
		Map<String, Object> pbr = new LinkedHashMap<String, Object>();
		float[] base = {
				((argb >> 16) & 0xff) / 255f,
				((argb >> 8) & 0xff) / 255f,
				(argb & 0xff) / 255f,
				((argb >>> 24) & 0xff) / 255f
		};
		pbr.put("baseColorFactor", floatList(base));
		pbr.put("metallicFactor", Double.valueOf(0.0));
		pbr.put("roughnessFactor", Double.valueOf(1.0));
		if (textured && tex != null) {
			int img = exportTextureImage(tex, colorKey);
			if (img >= 0) {
				Map<String, Object> texObj = new LinkedHashMap<String, Object>();
				texObj.put("source", Integer.valueOf(img));
				texObj.put("sampler", Integer.valueOf(sampler));
				gTextures.add(texObj);
				Map<String, Object> ref = new LinkedHashMap<String, Object>();
				ref.put("index", Integer.valueOf(gTextures.size() - 1));
				pbr.put("baseColorTexture", ref);
			}
		}
		mat.put("pbrMetallicRoughness", pbr);
		mat.put("doubleSided", Boolean.valueOf(doubleSided));
		if (blend != 0 || colorKey) {
			mat.put("alphaMode", colorKey && blend == 0 ? "MASK" : "BLEND");
			if (colorKey && blend == 0) mat.put("alphaCutoff", Double.valueOf(0.5));
		}
		if (!lighting) {
			Map<String, Object> ext = new LinkedHashMap<String, Object>();
			ext.put("KHR_materials_unlit", new LinkedHashMap<String, Object>());
			mat.put("extensions", ext);
			usedUnlit = true;
		}
		if (draw != null && draw.effect != null && draw.effect.sphere != null && (flags & Figure.MAT_SPECULAR) != 0) {
			int sph = exportTextureImage(draw.effect.sphere, false);
			Map<String, Object> extras = new LinkedHashMap<String, Object>();
			extras.put("mascotSphereMap", Integer.valueOf(sph));
			extras.put("mascotMaterialFlags", Integer.valueOf(flags));
			if (draw.effect.toon) {
				extras.put("mascotToon", Boolean.TRUE);
				extras.put("mascotToonThreshold", Integer.valueOf(draw.effect.toonThreshold));
				extras.put("mascotToonLow", Integer.valueOf(draw.effect.toonLow));
				extras.put("mascotToonHigh", Integer.valueOf(draw.effect.toonHigh));
			}
			mat.put("extras", extras);
		} else {
			Map<String, Object> extras = new LinkedHashMap<String, Object>();
			extras.put("mascotMaterialFlags", Integer.valueOf(flags));
			String blendName = blend == Figure.MAT_BLEND_ADD ? "add"
					: blend == Figure.MAT_BLEND_HALF ? "half"
					: blend == Figure.MAT_BLEND_SUB ? "sub" : "none";
			extras.put("mascotBlend", blendName);
			mat.put("extras", extras);
		}

		gMaterials.add(mat);
		int idx = gMaterials.size() - 1;
		materialCache.put(Long.valueOf(key), Integer.valueOf(idx));
		return idx;
	}

	private int exportTextureImage(Texture tex, boolean colorKey) {
		if (tex == null) return -1;
		Integer cached = imageCache.get(tex);
		if (cached != null) return cached.intValue();

		int w = tex.width > 0 ? tex.width : tex.paddedWidth;
		int h = tex.height > 0 ? tex.height : tex.paddedHeight;
		if (w <= 0 || h <= 0) return -1;
		int[] argb = new int[w * h];
		if (tex.isForModel && tex.bitmapData != null) {
			int[] pal = tex.origPalette != null ? tex.origPalette : tex.palette;
			int stride = tex.paddedWidth > 0 ? tex.paddedWidth : w;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int idx = tex.bitmapData[y * stride + x] & 0xff;
					int c = pal != null && idx < pal.length ? pal[idx] : 0xFFFFFFFF;
					if (colorKey && idx == 0) c = 0;
					argb[y * w + x] = c;
				}
			}
		} else if (tex.envmapData != null) {
			int stride = tex.paddedWidth > 0 ? tex.paddedWidth : w;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					argb[y * w + x] = 0xff000000 | tex.envmapData[y * stride + x];
				}
			}
		} else {
			return -1;
		}

		BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		bi.setRGB(0, 0, w, h, argb, 0, w);
		byte[] png;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(bi, "png", baos);
			png = baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		int bv = addBufferView(png, 0);
		Map<String, Object> img = new LinkedHashMap<String, Object>();
		img.put("bufferView", Integer.valueOf(bv));
		img.put("mimeType", "image/png");
		gImages.add(img);
		int idx = gImages.size() - 1;
		imageCache.put(tex, Integer.valueOf(idx));
		return idx;
	}

	private int defaultSampler() {
		if (!gSamplers.isEmpty()) return 0;
		Map<String, Object> s = new LinkedHashMap<String, Object>();
		s.put("wrapS", Integer.valueOf(10497));
		s.put("wrapT", Integer.valueOf(10497));
		s.put("magFilter", Integer.valueOf(9728));
		s.put("minFilter", Integer.valueOf(9728));
		gSamplers.add(s);
		return 0;
	}

	private int exportLight(MascotSceneCapture.EffectSnap fx) {
		if (fx == null || !fx.hasLight) return -1;
		float intensity = fx.dir / FP;
		if (intensity < 0f) intensity = 0f;
		Map<String, Object> gl = new LinkedHashMap<String, Object>();
		gl.put("type", "directional");
		gl.put("color", floatList(new float[]{1f, 1f, 1f}));
		gl.put("intensity", Double.valueOf(intensity));
		Map<String, Object> extras = new LinkedHashMap<String, Object>();
		extras.put("mascotAmbientIntensity", Integer.valueOf(fx.amb));
		extras.put("mascotDirIntensity", Integer.valueOf(fx.dir));
		extras.put("mascotDirection", Arrays.asList(
				Integer.valueOf(fx.lx), Integer.valueOf(fx.ly), Integer.valueOf(fx.lz)));
		gl.put("extras", extras);
		gLights.add(gl);
		usedLights = true;
		return gLights.size() - 1;
	}

	private static float[] lookAtLightMatrix(MascotSceneCapture.EffectSnap fx) {
		// Directional light in glTF shines down -Z of the node. Rotate so -Z matches (lx,ly,lz).
		float x = fx.lx / FP;
		float y = fx.ly / FP;
		float z = fx.lz / FP;
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		if (len < 1e-6f) return IDENTITY.clone();
		x /= len;
		y /= len;
		z /= len;
		// We want node -Z = light direction, so column2 = -dir
		float zx = -x, zy = -y, zz = -z;
		float ux = 0, uy = 1, uz = 0;
		if (Math.abs(zy) > 0.9f) {
			ux = 1;
			uy = 0;
			uz = 0;
		}
		float xx = uy * zz - uz * zy;
		float xy = uz * zx - ux * zz;
		float xz = ux * zy - uy * zx;
		float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
		if (xl < 1e-6f) return IDENTITY.clone();
		xx /= xl;
		xy /= xl;
		xz /= xl;
		float yx = zy * xz - zz * xy;
		float yy = zz * xx - zx * xz;
		float yz = zx * xy - zy * xx;
		return new float[]{
				xx, xy, xz, 0,
				yx, yy, yz, 0,
				zx, zy, zz, 0,
				0, 0, 0, 1
		};
	}

	private int exportCamera(MascotSceneCapture.ProjSnap proj) {
		if (proj == null) return -1;
		Map<String, Object> cam = new LinkedHashMap<String, Object>();
		if (proj.mode == 1) {
			double yfov;
			if (proj.layoutCmd == Graphics3D.COMMAND_PERSPECTIVE_FOV && proj.angle > 0) {
				double halfX = proj.angle / 4096.0 * Math.PI;
				double aspect = proj.fbH > 0 ? (double) proj.fbW / proj.fbH : 1.0;
				yfov = 2.0 * Math.atan(Math.tan(halfX) / aspect);
			} else if (proj.near > 0 && proj.perspH > 0) {
				yfov = 2.0 * Math.atan((proj.perspH * 0.5) / proj.near);
			} else {
				yfov = Math.toRadians(60);
			}
			if (yfov < 0.01) yfov = 0.01;
			if (yfov > Math.PI - 0.01) yfov = Math.PI - 0.01;
			Map<String, Object> persp = new LinkedHashMap<String, Object>();
			persp.put("yfov", Double.valueOf(yfov));
			if (proj.fbW > 0 && proj.fbH > 0) {
				persp.put("aspectRatio", Double.valueOf((double) proj.fbW / proj.fbH));
			}
			persp.put("znear", Double.valueOf(Math.max(proj.near, 1)));
			if (proj.far > proj.near) persp.put("zfar", Double.valueOf(proj.far));
			cam.put("type", "perspective");
			cam.put("perspective", persp);
		} else {
			float ymag;
			if (proj.scaleY != 0) {
				ymag = (proj.fbH > 0 ? proj.fbH : 240) * 0.5f * FP / proj.scaleY;
			} else if (proj.parallelH > 0) {
				ymag = proj.parallelH * 0.5f;
			} else {
				ymag = 120f;
			}
			float aspect = proj.fbW > 0 && proj.fbH > 0 ? (float) proj.fbW / proj.fbH : 1f;
			Map<String, Object> ortho = new LinkedHashMap<String, Object>();
			ortho.put("xmag", Double.valueOf(ymag * aspect));
			ortho.put("ymag", Double.valueOf(ymag));
			ortho.put("znear", Double.valueOf(proj.near > 0 ? proj.near : 1));
			ortho.put("zfar", Double.valueOf(proj.far > 0 ? proj.far : 32767));
			cam.put("type", "orthographic");
			cam.put("orthographic", ortho);
		}
		gCameras.add(cam);
		return gCameras.size() - 1;
	}

	private static float[] affineToGltf(AffineTrans a) {
		if (a == null) return IDENTITY.clone();
		return new float[]{
				a.m00 / FP, a.m10 / FP, a.m20 / FP, 0,
				a.m01 / FP, a.m11 / FP, a.m21 / FP, 0,
				a.m02 / FP, a.m12 / FP, a.m22 / FP, 0,
				a.m03, a.m13, a.m23, 1
		};
	}

	private static void decomposeAffine(AffineTrans a, float[] t, float[] q, float[] s) {
		t[0] = a.m03;
		t[1] = a.m13;
		t[2] = a.m23;
		float r00 = a.m00 / FP, r10 = a.m10 / FP, r20 = a.m20 / FP;
		float r01 = a.m01 / FP, r11 = a.m11 / FP, r21 = a.m21 / FP;
		float r02 = a.m02 / FP, r12 = a.m12 / FP, r22 = a.m22 / FP;
		s[0] = len3(r00, r10, r20);
		s[1] = len3(r01, r11, r21);
		s[2] = len3(r02, r12, r22);
		if (s[0] > 1e-8f) {
			r00 /= s[0];
			r10 /= s[0];
			r20 /= s[0];
		}
		if (s[1] > 1e-8f) {
			r01 /= s[1];
			r11 /= s[1];
			r21 /= s[1];
		}
		if (s[2] > 1e-8f) {
			r02 /= s[2];
			r12 /= s[2];
			r22 /= s[2];
		}
		float det = r00 * (r11 * r22 - r21 * r12) - r01 * (r10 * r22 - r20 * r12) + r02 * (r10 * r21 - r20 * r11);
		if (det < 0f) {
			s[0] = -s[0];
			r00 = -r00;
			r10 = -r10;
			r20 = -r20;
		}
		quatFromMatrix(r00, r01, r02, r10, r11, r12, r20, r21, r22, q);
	}

	private static float len3(float x, float y, float z) {
		return (float) Math.sqrt(x * x + y * y + z * z);
	}

	private static void quatFromMatrix(float r00, float r01, float r02,
									   float r10, float r11, float r12,
									   float r20, float r21, float r22,
									   float[] q) {
		float trace = r00 + r11 + r22;
		if (trace > 0f) {
			float s = (float) Math.sqrt(trace + 1f) * 2f;
			q[3] = 0.25f * s;
			q[0] = (r21 - r12) / s;
			q[1] = (r02 - r20) / s;
			q[2] = (r10 - r01) / s;
		} else if (r00 > r11 && r00 > r22) {
			float s = (float) Math.sqrt(1f + r00 - r11 - r22) * 2f;
			q[3] = (r21 - r12) / s;
			q[0] = 0.25f * s;
			q[1] = (r01 + r10) / s;
			q[2] = (r02 + r20) / s;
		} else if (r11 > r22) {
			float s = (float) Math.sqrt(1f + r11 - r00 - r22) * 2f;
			q[3] = (r02 - r20) / s;
			q[0] = (r01 + r10) / s;
			q[1] = 0.25f * s;
			q[2] = (r12 + r21) / s;
		} else {
			float s = (float) Math.sqrt(1f + r22 - r00 - r11) * 2f;
			q[3] = (r10 - r01) / s;
			q[0] = (r02 + r20) / s;
			q[1] = (r12 + r21) / s;
			q[2] = 0.25f * s;
		}
		float n = len3(q[0], q[1], q[2]);
		n = (float) Math.sqrt(n * n + q[3] * q[3]);
		if (n > 1e-8f) {
			q[0] /= n;
			q[1] /= n;
			q[2] /= n;
			q[3] /= n;
		} else {
			q[0] = q[1] = q[2] = 0f;
			q[3] = 1f;
		}
	}

	private static boolean invertMatrix(float[] m, float[] out) {
		float[] inv = new float[16];
		inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
				+ m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
		inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
				- m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
		inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
				+ m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
		inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
				- m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
		inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
				- m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
		inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
				+ m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
		inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
				- m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
		inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
				+ m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
		inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
				+ m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
		inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
				- m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
		inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
				+ m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
		inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
				- m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
		inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
				- m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
		inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
				+ m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
		inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
				- m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
		inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
				+ m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
		float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
		if (Math.abs(det) < 1e-12f) return false;
		det = 1f / det;
		for (int i = 0; i < 16; i++) out[i] = inv[i] * det;
		return true;
	}

	private void align4() {
		int pad = (4 - (bin.size() % 4)) % 4;
		for (int i = 0; i < pad; i++) bin.write(0);
	}

	private int addBufferView(byte[] data, int target) {
		align4();
		int offset = bin.size();
		bin.write(data, 0, data.length);
		Map<String, Object> bv = new LinkedHashMap<String, Object>();
		bv.put("buffer", Integer.valueOf(0));
		bv.put("byteOffset", Integer.valueOf(offset));
		bv.put("byteLength", Integer.valueOf(data.length));
		if (target != 0) bv.put("target", Integer.valueOf(target));
		gBufferViews.add(bv);
		return gBufferViews.size() - 1;
	}

	private int writeAccessor(float[] data, int comps, boolean withBounds) {
		ByteBuffer bb = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) bb.putFloat(data[i]);
		int bv = addBufferView(bb.array(), comps == 1 ? 0 : 34962);
		Map<String, Object> acc = new LinkedHashMap<String, Object>();
		acc.put("bufferView", Integer.valueOf(bv));
		acc.put("componentType", Integer.valueOf(5126));
		acc.put("count", Integer.valueOf(data.length / comps));
		acc.put("type", comps == 1 ? "SCALAR" : comps == 2 ? "VEC2" : comps == 3 ? "VEC3" : "VEC4");
		if (withBounds) {
			float[] min = new float[comps], max = new float[comps];
			Arrays.fill(min, Float.MAX_VALUE);
			Arrays.fill(max, -Float.MAX_VALUE);
			for (int i = 0; i < data.length; i += comps) {
				for (int c = 0; c < comps; c++) {
					min[c] = Math.min(min[c], data[i + c]);
					max[c] = Math.max(max[c], data[i + c]);
				}
			}
			acc.put("min", floatList(min));
			acc.put("max", floatList(max));
		}
		gAccessors.add(acc);
		return gAccessors.size() - 1;
	}

	private int writeJointsAccessor(int[] joints, int vertexCount) {
		ByteBuffer jb = ByteBuffer.allocate(joints.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < joints.length; i++) jb.putShort((short) joints[i]);
		int bv = addBufferView(jb.array(), 34962);
		Map<String, Object> acc = new LinkedHashMap<String, Object>();
		acc.put("bufferView", Integer.valueOf(bv));
		acc.put("componentType", Integer.valueOf(5123));
		acc.put("count", Integer.valueOf(vertexCount));
		acc.put("type", "VEC4");
		gAccessors.add(acc);
		return gAccessors.size() - 1;
	}

	private int writeIndexAccessor(int[] indices) {
		int max = 0;
		for (int i = 0; i < indices.length; i++) if (indices[i] > max) max = indices[i];
		boolean useShort = max < 65536;
		ByteBuffer bb;
		int componentType;
		if (useShort) {
			bb = ByteBuffer.allocate(indices.length * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < indices.length; i++) bb.putShort((short) indices[i]);
			componentType = 5123;
		} else {
			bb = ByteBuffer.allocate(indices.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < indices.length; i++) bb.putInt(indices[i]);
			componentType = 5125;
		}
		int bv = addBufferView(bb.array(), 34963);
		Map<String, Object> acc = new LinkedHashMap<String, Object>();
		acc.put("bufferView", Integer.valueOf(bv));
		acc.put("componentType", Integer.valueOf(componentType));
		acc.put("count", Integer.valueOf(indices.length));
		acc.put("type", "SCALAR");
		gAccessors.add(acc);
		return gAccessors.size() - 1;
	}

	private static float[] toFloatArray(List<Float> list) {
		float[] a = new float[list.size()];
		for (int i = 0; i < a.length; i++) a[i] = list.get(i).floatValue();
		return a;
	}

	private static List<Float> floatList(float[] arr) {
		List<Float> list = new ArrayList<Float>(arr.length);
		for (int i = 0; i < arr.length; i++) list.add(Float.valueOf(arr[i]));
		return list;
	}

	private void writeGlb(File outFile, List<Integer> roots) throws IOException {
		Map<String, Object> asset = new LinkedHashMap<String, Object>();
		asset.put("version", "2.0");
		asset.put("generator", "KEmulator MascotCapsule Exporter");

		Map<String, Object> buffer = new LinkedHashMap<String, Object>();
		buffer.put("byteLength", Integer.valueOf(bin.size()));

		Map<String, Object> scene = new LinkedHashMap<String, Object>();
		scene.put("nodes", roots);
		scene.put("name", "MascotCapsuleScene");

		Map<String, Object> root = new LinkedHashMap<String, Object>();
		root.put("asset", asset);
		root.put("scene", Integer.valueOf(0));
		root.put("scenes", Collections.singletonList(scene));
		root.put("nodes", gNodes);
		if (!gMeshes.isEmpty()) root.put("meshes", gMeshes);
		if (!gMaterials.isEmpty()) root.put("materials", gMaterials);
		if (!gTextures.isEmpty()) root.put("textures", gTextures);
		if (!gImages.isEmpty()) root.put("images", gImages);
		if (!gSamplers.isEmpty()) root.put("samplers", gSamplers);
		if (!gCameras.isEmpty()) root.put("cameras", gCameras);
		if (!gSkins.isEmpty()) root.put("skins", gSkins);
		if (!gAnimations.isEmpty()) root.put("animations", gAnimations);
		if (!gAccessors.isEmpty()) root.put("accessors", gAccessors);
		if (!gBufferViews.isEmpty()) root.put("bufferViews", gBufferViews);
		root.put("buffers", Collections.singletonList(buffer));

		List<String> extUsed = new ArrayList<String>();
		if (usedLights) extUsed.add("KHR_lights_punctual");
		if (usedUnlit) extUsed.add("KHR_materials_unlit");
		if (!extUsed.isEmpty()) root.put("extensionsUsed", extUsed);

		if (usedLights) {
			Map<String, Object> khrLights = new LinkedHashMap<String, Object>();
			khrLights.put("lights", gLights);
			Map<String, Object> extensions = new LinkedHashMap<String, Object>();
			extensions.put("KHR_lights_punctual", khrLights);
			root.put("extensions", extensions);
		}

		byte[] jsonBytes = toJson(root).getBytes("UTF-8");
		int jsonPad = (4 - (jsonBytes.length % 4)) % 4;
		int jsonChunkLen = jsonBytes.length + jsonPad;

		byte[] binBytes = bin.toByteArray();
		int binPad = (4 - (binBytes.length % 4)) % 4;
		int binChunkLen = binBytes.length + binPad;

		int totalLen = 12 + 8 + jsonChunkLen + 8 + binChunkLen;

		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
		try {
			writeLE(out, 0x46546C67);
			writeLE(out, 2);
			writeLE(out, totalLen);
			writeLE(out, jsonChunkLen);
			writeLE(out, 0x4E4F534A);
			out.write(jsonBytes);
			for (int i = 0; i < jsonPad; i++) out.write(0x20);
			writeLE(out, binChunkLen);
			writeLE(out, 0x004E4942);
			out.write(binBytes);
			for (int i = 0; i < binPad; i++) out.write(0);
		} finally {
			out.close();
		}
	}

	private static void writeLE(DataOutputStream out, int value) throws IOException {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}

	private static String toJson(Object o) {
		StringBuilder sb = new StringBuilder();
		writeJson(o, sb);
		return sb.toString();
	}

	private static void writeJson(Object o, StringBuilder sb) {
		if (o == null) {
			sb.append("null");
			return;
		}
		if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append('\"').append(escape(e.getKey().toString())).append("\":");
				writeJson(e.getValue(), sb);
			}
			sb.append('}');
		} else if (o instanceof List) {
			sb.append('[');
			boolean first = true;
			for (Object v : (List<?>) o) {
				if (!first) sb.append(',');
				first = false;
				writeJson(v, sb);
			}
			sb.append(']');
		} else if (o instanceof String) {
			sb.append('\"').append(escape((String) o)).append('\"');
		} else if (o instanceof Number || o instanceof Boolean) {
			sb.append(o.toString());
		} else {
			sb.append('\"').append(escape(o.toString())).append('\"');
		}
	}

	private static String escape(String s) {
		StringBuilder r = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\"':
					r.append("\\\"");
					break;
				case '\\':
					r.append("\\\\");
					break;
				case '\n':
					r.append("\\n");
					break;
				default:
					r.append(c);
			}
		}
		return r.toString();
	}
}
else {
			sb.append('\"').append(escape(o.toString())).append('\"');
		}
	}

	private static String escape(String s) {
		StringBuilder r = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\"':
					r.append("\\\"");
					break;
				case '\\':
					r.append("\\\\");
					break;
				case '\n':
					r.append("\\n");
					break;
				default:
					r.append(c);
			}
		}
		return r.toString();
	}
}
