# Bomberman 3D — missing background / `texImage2D: glError 1282`

## Bottom line

This is **not a missing background image in the JAR**. The game's opening/game "background" is a **lit, untextured 3D quad** submitted through JBlend/Mascot Capsule `Graphics3D.renderPrimitives()`. It disappears because KEmulator's MC3D renderer aborts the whole frame inside its 2D/3D compositing step before that queued quad is drawn or before the GL result is read back.

The exact log line comes from `src/3d/ru/woesss/j2me/micro3d/Render.java`, where `checkGlError("texImage2D")` is called immediately after `GL11.glTexImage2D()` in `copy2d(boolean)`. `1282` is `GL_INVALID_OPERATION`; `glGetError()` returns a single sticky error flag, so this check can also report a **stale earlier GL error** rather than proving that this particular `glTexImage2D` was the offending call ([glGetError reference](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glGetError.xhtml), [glTexImage2D reference](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glTexImage2D.xhtml)). In either case the thrown `RuntimeException` happens in `Render.flushToBuffer()` **before** the queued background quad and final `swapBuffers()` run, which explains both the popup and the blank background.

**Conclusion:** the game is exercising an unusual but valid mixed 2D/3D rendering pattern. The fault is on KEmulator's JBlend/MC3D GL path — specifically the 2D-backbuffer-to-texture copy and/or GL state/context handling — not a bad/missing game asset.

---

## 1. Suite-driven decompilation

I followed the linked [LLM-J2ME-SUITE `Agents.md`](https://github.com/klaxons1/LLM-J2ME-SUITE/blob/main/Agents.md):

- Only **Sporeflower** was used, from the suite's `libs/sporeflower-linux-x86_64.tar.gz` (`v2026.11.10.47.1`).
- Input: `/tmp/arena-coding-uploads/bomberman3d.jar.txt` — a valid JAR despite the `.txt` suffix; copied as `/tmp/bomberman3d.jar`.
- Obfuscation check: the six classes are `BomberMan`, `BomberManCanvas`, `Bluetooth`, `CommandUtil`, `CustomException`, `MessageQueue`; they are **not** named `a`, `b`, `c`, so the JAR is not obfuscated by the suite criterion.
- Manifest: `CLDC-1.1`, `MIDP-2.0`, `MIDlet-1: ..., icon_2.png, BomberMan`, vendor `(c)HUDSON SOFT`, `Created-By: 1.4.2_07`.
- Sporeflower result: **6 classes, 225 methods, 0 failed classes, 0 failed methods, 0 fallback methods**.
- The initial `compile-stubs` check failed only because Sporeflower could not infer declarations for external APIs: `com.jblend.graphics.j3d.Graphics3D` and the Vodafone Bluetooth listener/session/device types. Per `Agents.md`, I fixed those by adding minimal API stubs, used the suite's UEI jars and the suite-referenced Linux JDK 8, and recompiled: **compile check passed (13 sources)**. I also cross-checked the original JAR bytecode with `javap -c`, especially `clearBG`, `renderGame`, and `renderTitle`; the decompiled control flow matches.
- I did **not** run freej2me-plus; `Agents.md` says to ask before that time-consuming verification step.

The full generated project is in `/tmp/bomberman3d-project` (`decompiled/`, `resources/`, `out/decompile.json`, `out/coverage.md`, `out/semantic-summary.md`). Those are scratch/intermediate outputs and are deliberately not added to Git.

---

## 2. What the decompiled game actually draws

`BomberManCanvas` imports `com.jblend.graphics.j3d.*` and casts the same `javax.microedition.lcdui.Graphics` object to `Graphics3D`:

```java
Graphics3D v2 = (Graphics3D) (java.lang.Object) p1;
```

KEmulator's `src/midp/javax/microedition/lcdui/Graphics.java:19-22` implements `com.jblend.graphics.j3d.Graphics3D`; `flush()` at line 678 delegates to `RenderProxy.flush(this)`. So one `Graphics` object is used for both LCDUI 2D drawing and MC3D 3D rendering.

### Background

`clearBG(Graphics3D)` (`decompiled/BomberManCanvas.java:5453`) does **not** load or bind a background texture. It submits one colored quad:

```java
this.effect3D.setLight(new Light(new Vector3D(0, 0, -4096), 4096, 0));
this.layout.setAffineTrans(this.identity);
p1.renderPrimitives(
    (Texture) null, 0, 0, this.layout, this.effect3D,
    0x4000000 | 0x300 | 0x400 | 1, 1,
    vertexCoords, normals, null, colorBG);
```

Decoded command bits:

- `0x4000000` = `PRIMITIVE_QUADS`;
- `0x300` = `PDATA_NORMAL_PER_VERTEX`;
- `0x400` = `PDATA_COLOR_PER_COMMAND`;
- `1` = `PATTR_LIGHTING`;
- `1` primitive, no texture, color from `colorBG`.

The original bytecode (`javap -c`) confirms `aconst_null`, `ldc_w 67108864`, `sipush 768`, `sipush 1024`, `iconst_1`, then `invokeinterface Graphics3D.renderPrimitives(..., 12)`. So the visible "background" is a **far-plane lit quad**, not a BMP/PNG. This also explains why upstream commit `0755ba7c` ("Replace glDepthFunc GL_LESS to GL_LEQUAL", "Fixes gradient background render in bomberman3d on nvidia") was relevant: a lit quad at the far depth plane is sensitive to depth-test equality on some drivers. That fix is already in the current HEAD.

### Frame order

`renderGame` (`decompiled/BomberManCanvas.java:11674`), `renderTitle` (`:2090`), `renderStage` (`:2231`), and `renderArea` (`:2414`) all follow the same shape:

```java
setView(v2, ...);
clearBG(v2);
setLight(v2, 4096);
// many renderPrimitives / renderFigure calls
v2.flush();
// then LCDUI 2D HUD/sprites on the same Graphics object
```

For example `renderGame` calls `v2.flush()` at `:11789` and then `setClip` / `renderStatus` / 2D sprites. `drawSpriteS(Graphics3D)` (`:5793`) even calls `p1.flush()` again in the middle of 2D UI. This repeated mixed 2D/3D use is the important game behavior.

### Assets

`moveInit()` (`:1873`) loads `Image.createImage("/" + (200 + v))` for LCDUI images and `Phrase("/" + (800 + v))` for sound. `loadImage(int)` (`:5317`) loads `new Texture(loadData(p1), true)` from numeric resources. The extracted assets are:

| Resource | Kind | Size |
|---|---:|---:|
| `0`, `1` | 8-bit palettized BMP | 256x256 |
| `2`–`5` | 8-bit palettized BMP | 144x189 |
| `10`, `11` | 8-bit palettized BMP | 128x128 |
| `20` | 8-bit palettized BMP | 54x72 |
| `30` | 8-bit palettized BMP | 90x18 |
| `60`, `61` | 8-bit palettized BMP | 96x96 |
| `501` | 8-bit palettized BMP | 128x64 |
| `200`–`206` | indexed-color PNG | 256x256, 168x24, 240x224, 190x200, 224x224, 100x120, 220x130 |
| `icon_2.png` | indexed-color PNG | 24x24 |
| `800`–`846` | `MMMD` SMAF/audio data | — |

The suite's M3G 8-bit-texture/256x tips are not directly applicable because this is **JBlend/MC3D, not JSR-184/M3G**, but the 8-bit BMPs still matter: KEmulator's MC3D loader decodes them to RGBA and `TextureImpl.loadToGL()` uploads them with NPOT dimensions.

---

## 3. Exact KEmulator failure path

1. `Graphics.flush()` → `src/3d/com/jblend/graphics/j3d/RenderProxy.java:96` → `getRender(g).flushToBuffer()`.
2. `getRender(g)` (`RenderProxy.java:140`) creates one `Render` per `Graphics`, calls `render.bind(g, false)` once, and caches it in a `WeakHashMap`.
3. `Render.flushToBuffer()` (`src/3d/ru/woesss/j2me/micro3d/Render.java:1472`) runs on the GL/`Emulator3D` thread:
   - `copy2d(true)` at line 1480;
   - then `flushStep = 1` and `2`, rendering all queued 3D nodes (including `clearBG`'s colored quad);
   - then `glFlush()`, `swapBuffers()`/`glReadPixels`, clear depth, and clear the stack.
4. `copy2d(boolean)` (`Render.java:237`):
   - binds/generates `bgTextureId` on `GL_TEXTURE1`;
   - sets nearest/linear filters and `GL_CLAMP_TO_EDGE`;
   - gets `targetGraphics.getImage()`;
   - chooses `texFormat = targetImage.isTransparent() ? GL_RGBA : GL_RGB`;
   - calls `GL11.glTexImage2D(...)` at lines 259-263 with the converted 2D backbuffer pixels;
   - calls `checkGlError("texImage2D")` at line 264;
   - **only afterwards** sets `GL_UNPACK_ALIGNMENT = 1` (line 266), switches to `GL_TEXTURE0`, binds `bgTextureId` there (267-268), and draws a full-screen quad with `Program.Simple` (sampler unit 1).
5. `checkGlError` (`Render.java:82`) is a single `glGetError()`:

```java
int error = glGetError();
if (error != GL_NO_ERROR) {
    System.err.println(glOperation + ": glError " + error);
    throw new RuntimeException(glOperation + ": glError " + error);
}
```

Because `flushToBuffer()` has not yet rendered the queued stack when `copy2d(true)` throws, the colored background quad is never drawn, `swapBuffers()` never runs, and the `finally` block clears the queued stack. The game continues to draw its 2D HUD, so the user sees "no background" plus repeated log/exception popups.

`Program.Simple` samples texture unit 1 (`src/3d/ru/woesss/j2me/micro3d/Program.java:225-239`), while `Program.Tex`/`Sprite` use unit 0. The post-upload `glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, bgTextureId.get(0))` added in commit `40727e69` is suspicious: it binds the same backbuffer texture on unit 0 as well as unit 1. That commit also moved `GL_UNPACK_ALIGNMENT = 1` from **before** `glTexImage2D` to **after** it (it had been placed before the upload by `de47d2ee`, "Try to fix 2d copy in mascot"). That history is a strong indicator that the MC3D 2D-copy/GL-state code is still fragile.

---

## 4. What can set `1282` here

`GL_INVALID_OPERATION` means the operation is not allowed in the current GL state ([Khronos `glGetError`](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glGetError.xhtml)). For `glTexImage2D`, documented `GL_INVALID_OPERATION` causes include a bound/mapped or undersized `GL_PIXEL_UNPACK_BUFFER`, or an incompatible internal format/format/type combination ([Khronos `glTexImage2D`](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glTexImage2D.xhtml)); calling it between `glBegin`/`glEnd` is another legacy cause. KEmulator's call uses `GL_RGB`/`GL_RGBA` with matching `GL_UNSIGNED_BYTE`, so the arguments themselves look legal on a modern GL context. The remaining evidence-backed possibilities are:

1. **Stale error from an unchecked GL call.** `checkGlError("texImage2D")` calls `glGetError()` only once and does not loop until `GL_NO_ERROR`; any earlier error flag is mislabeled as `texImage2D`. `TextureImpl.loadToGL()` (`TextureImpl.java:151-160`) uploads game BMP textures with no immediate error check after `glTexImage2D`. `Emulator3D.bindTarget()` sets `GL_UNPACK_ALIGNMENT=1` asynchronously (`Emulator3D.java:250`), and the M3G `drawBackground()` path temporarily sets `GL_UNPACK_ROW_LENGTH/SKIP_ROWS/SKIP_PIXELS` (`Emulator3D.java:592-608`). Leaked pixel-store or `GL_PIXEL_UNPACK_BUFFER` state can make a later texture upload invalid.
2. **NPOT textures are a clue, not a proven cause.** The game and the 2D backbuffer use NPOT sizes (e.g. 144x189, 240x224, 240x320). `Emulator3D.getCapabilities()` checks `OpenGL14/20/30` but not `GL_ARB_texture_non_power_of_two`. However, `Program.create()` uses `GL20.glCreateShader/glCreateProgram` (`Program.java:79,110`), so a working MC3D-GL context is effectively GL 2.0+; NPOT alone should not generate `1282` there. It may still matter on old/driver-limited GL 1.x contexts, but it is not the best explanation for modern Nvidia/AMD reports.
3. **UI/log-window reentrancy.** Upstream issue [#301](https://github.com/shinovon/KEmulator/issues/301) is exactly "Bomberman 3D BT (Trial): The opening background is not displayed." Its comments report that `ShowLogFrame=true` reproduces rendering problems and deleting/resetting `property.txt` fixes them; the maintainer reopened it as "very strange". `Log.createWindow()` (`src/main/emulator/ui/swt/Log.java:114-131`) runs a **nested SWT event loop**:

   ```java
   while (!this.logShell.isDisposed()) {
       if (!current.readAndDispatch()) current.sleep();
   }
   ```

   It is scheduled from `EmulatorScreen.java:369`. This can interleave UI/paint events with the M3G/MC3D GL thread and is consistent with driver/context-dependent `GL_INVALID_OPERATION`. The same issue notes the earlier depth-function fix; current HEAD already contains `GL_LEQUAL`.
4. **Driver-specific colored-quad/depth issues.** Upstream #301 and [#300](https://github.com/shinovon/KEmulator/issues/300) mention vendor-dependent behavior and "something with colored quad primitives". That is a separate way the background quad can be hidden, but it does not produce the `texImage2D` exception; the exception aborts the frame earlier.

Static analysis cannot prove which single GL command first sets `1282` on the user's GPU/driver. What is proven is that KEmulator's `copy2d(true)` check is the point where the error is observed and where rendering is aborted.

---

## 5. Practical fixes / diagnostics

This section is the original plan. The diagnosis and the opt-in instrumentation in section 7 did not change rendering behavior; **the rendering fixes are now implemented in section 10**. In priority order:

1. **Make GL error reporting accurate.** In `Render.checkGlError`, drain errors in a loop and report every flag; call `glGetError()` before `copy2d(true)` to discard/report stale state. Add a `checkGlError("TextureImpl.loadToGL")` immediately after `TextureImpl.loadToGL()`'s `glTexImage2D`. This will tell whether the `1282` originates in game texture uploads, the backbuffer upload, or an earlier GL call.
2. **Normalize pixel-store state before `copy2d`'s upload.** Move `glPixelStorei(GL_UNPACK_ALIGNMENT, 1)` back **before** `GL11.glTexImage2D`; explicitly reset `GL_UNPACK_ROW_LENGTH`, `GL_UNPACK_SKIP_ROWS`, `GL_UNPACK_SKIP_PIXELS`; unbind/check `GL_PIXEL_UNPACK_BUFFER_BINDING`; and verify `GL_MAX_TEXTURE_SIZE`/NPOT capability. Keep the active texture unit consistent with the sampler (`Program.Simple` uses unit 1).
3. **Do not abort the whole frame on a failed 2D copy.** `flushToBuffer()` calls `copy2d(true)` unconditionally, unlike `Render.flush()`/`drawFigure()` which respect `mascotIgnoreBackground`. Either honor `AppSettings.mascotIgnoreBackground`/`mascotNo2DMixing` there, or make `copy2d` log the GL error and fall back to a black background, then still render the queued 3D stack. That alone should restore the colored background quad even when 2D mixing is broken.
4. **Fix or avoid the log-window nested event loop.** As an immediate user workaround, set `ShowLogFrame=false` in `property.txt` (or delete/reset the config), matching upstream #301. A code fix should avoid `Log.createWindow()`'s nested `readAndDispatch()` loop and keep GL context ownership on the M3G thread.
5. **If a GL 1.x/NPOT-limited context is confirmed**, pad/rescale MC3D BMP textures and the 2D backbuffer to power-of-two dimensions, or use a rectangle-texture path. Do not assume NPOT is the cause on GL 2.0+ without runtime evidence.

---

## 6. Confidence

- **High:** no missing asset; `clearBG` is a colored 3D quad; the error string comes from `Render.copy2d`; the thrown exception aborts `flushToBuffer()` before the quad/readback, so the background disappears.
- **High:** the game's mixed 2D/3D `flush()` pattern is valid for JBlend/MC3D and is what triggers KEmulator's 2D-backbuffer copy path.
- **Medium:** the original `1282` is caused by stale/leaked GL state or log-window/UI reentrancy on the MC3D path; upstream #301 independently correlates `ShowLogFrame=true` with the symptom.
- **New live-context evidence:** a later AMD Radeon R5 M230 / 4.6 compatibility-profile log shows `NPOT=true`, `pixelUnpackBuffer=0`, `unpackAlignment=1`, `unpackRowLength=0`, `unpackSkipRows=0`, `unpackSkipPixels=0`, `maxTextureSize=16384`, and no GL error during successful `copy2d` uploads. NPOT, PBO, and leaked pixel-store state are therefore ruled out on that GPU/driver.
- **Still not proven:** the exact first GL command that sets `1282` on the originally affected setup. Runtime GL instrumentation on that setup is still needed for final attribution.

---

## 7. Debug instrumentation added

A later request, “make more good debug output”, added opt-in MC3D GL diagnostics. This is **instrumentation**, not a rendering fix: it does not normalize pixel-store state, skip the 2D/3D composite, or change the background workaround. When GL errors occur on a live context, checks can now fail earlier with an accurate label, and diagnostic state queries clear their own error flags; normal rendering paths are unchanged when no GL errors occur. A shutdown/context-loss guard (section 8) prevents force-close teardown from being reported as a game bug.

### Enable it

- In **Application Settings → 3D → MascotCapsule LWJGL**, tick **GL debug output**.
- Or set `MascotGLDebug=true` in the app's `property.txt`.
- The setting is `AppSettings.mascotGlDebug`, loaded from `MascotGLDebug` and saved by `AppSettings.save()`. Verbose frame tracing is off by default.

Error diagnostics are always improved; the setting controls the extra per-frame/context/upload traces. All lines go to `System.err` with the `[MC3D-GL]` prefix, so they appear in the normal emulator console/log window.

### What the output now contains

- `Render.checkGlError()` drains **all** pending GL errors in a loop, prints each numeric code with its symbolic name (`1282 (GL_INVALID_OPERATION)`), and throws with the first named error instead of reporting a single sticky flag.
- Before `copy2d`, `TextureImpl.loadToGL`, `flush`, `drawFigure`, `release`, and `flushToBuffer`, a stale-error check attributes errors to the correct operation rather than the later `texImage2D` check.
- On any GL error, a state snapshot is printed: vendor, renderer, version, `GL_MAX_TEXTURE_SIZE`, active texture unit, 2D texture binding, unpack alignment/row length/skip rows/skip pixels, current program (GL 2.0+), pixel-unpack-buffer binding (GL 2.1+), NPOT capability, last frame, and last texture upload.
- Every `glTexImage2D` upload logs its label, target, level, internal format, width/height, border, format, type, texture ID, and buffer position/remaining/capacity. In verbose mode it also logs the pre-upload GL state.
- Frame traces include frame number, stack size, target type/size, clip, `backCopied`, render step, and completion/abort. Failures also print the exception stack trace and the GL state snapshot. Verbose traces print the first 30 frames and every 60th frame afterward to avoid flooding at 30+ FPS. High-frequency bind, `copy2d`, and texture-upload lines use the same frame schedule; upload metadata is still retained for error reports even when the line is not printed.
- Diagnostic state queries clear their own GL errors so the instrumentation itself cannot create the next frame’s mystery `1282`.
- `Emulator3D.isExiting()` plus a null-version/zero-`GL_MAX_TEXTURE_SIZE` check detects emulator shutdown or a lost GL context. Frame entry, `copy2d`, texture upload, bind, render, and clip paths then skip GL work instead of throwing. The condition is logged once, pending shutdown errors are cleared, and repeated identical `glGetError()` values are capped at three lines.
- State dumps now recognize a missing context and print a concise `GL state unavailable` line instead of misleading `vendor=<null>`, `maxTextureSize=0`, and `activeUnit=-33984` values. Frame entry resets the displayed render step, so a new frame no longer reports the previous frame’s `step=complete`.

Typical failure lines look like:

```text
[MC3D-GL] texImage2D: glError 1282 (GL_INVALID_OPERATION)
[MC3D-GL] texImage2D: 1 GL error(s) drained; first=GL_INVALID_OPERATION
[MC3D-GL] texImage2D: GL state vendor=..., renderer=..., version=..., maxTextureSize=..., activeUnit=1, texture2D=..., unpackAlignment=..., unpackRowLength=..., lastFrame=frame 12: flushToBuffer: enter ..., lastTextureUpload=copy2d.texImage2D: glTexImage2D ..., size=240x320, format=GL_RGBA, ...
[MC3D-GL] [ERROR] frame 12: flushToBuffer aborted at copy2d(true), stack=..., target=..., clip=..., backCopied=false, exception=java.lang.RuntimeException: texImage2D: glError 1282 (GL_INVALID_OPERATION)
```

### Files

- `src/3d/ru/woesss/j2me/micro3d/GLDebug.java` — new structured GL diagnostic helper.
- `src/3d/ru/woesss/j2me/micro3d/Render.java` — named/drained error checks, stale-error checks, upload parameters, frame/step traces, failure state dumps.
- `src/3d/ru/woesss/j2me/micro3d/TextureImpl.java` — immediate check after game texture uploads and upload-parameter logging.
- `src/main/emulator/AppSettings.java` — `MascotGLDebug` property, default, load, and save.
- `src/main/emulator/ui/swt/AppSettingsUI.java` — **GL debug output** checkbox in the MascotCapsule LWJGL group.
- `src/3d/emulator/graphics3D/lwjgl/Emulator3D.java` — visible `exiting` flag and `isExiting()` for shutdown-aware diagnostics.

The full desktop source set was compiled with the repository’s JDK 8 / LWJGL / SWT classpath: **compile check passed** (only pre-existing internal-API and unchecked-operation warnings). No-GL smoke tests also confirmed the symbolic error names, `[MC3D-GL]` prefix, and that the shutdown/context-loss guard returns `true` without throwing. No GL/X emulator run or freej2me-plus verification was performed, so runtime behavior on the affected GPU still needs a real reproduction with `MascotGLDebug=true`.

---

## 8. Force-close log is a shutdown/context-loss artifact

The user later supplied a log captured while force-closing the emulator. It showed 32 identical `flushToBuffer [stale before operation]: glError 1282` lines, then a state dump with `vendor=<null>`, `renderer=<null>`, `version=<null>`, `maxTextureSize=0`, `activeUnit=-33984`, and all other GL state values at zero. That pattern means there was **no current GL context** by the time the M3G thread processed queued frame 251: `Emulator3D.exit()` had begun teardown, and `glGetError()` kept returning `GL_INVALID_OPERATION` from the destroyed/lost context. `NPOT=true` in that dump comes from the cached `GLCapabilities` object, not from a live context.

This is not evidence for the original missing-background bug. The `lastTextureUpload` line is a normal backbuffer upload: `GL_RGB`, `240x320`, `textureId=1`, and `DirectByteBuffer[pos=76800, remaining=230400, capacity=307200]` — the expected 230400-byte RGB tail slice of a 307200-byte buffer. The exception stack (`GLDebug.drain` → `checkStale` → `flushToBuffer`) was the new stale-error check correctly seeing sticky shutdown errors, but then treating them like a live-context rendering error and throwing. The old `step=complete` shown on frame 251’s entry line was also stale: `beginFrame()` logged before resetting `flushStep` from the previous frame.

The debug instrumentation was tightened accordingly:

- `Emulator3D.exiting` is now volatile and exposed through `isExiting()`.
- `GLDebug.skipShutdownOrContextLoss()` checks the shutdown flag and, when necessary, detects a missing context via a null `GL_VERSION` or zero `GL_MAX_TEXTURE_SIZE`. It logs one concise suppression line, clears pending shutdown errors, and returns `true` so callers skip GL work.
- `flushToBuffer`, `flush`, `drawFigure`, `release`, `copy2d`, `TextureImpl.loadToGL`, bind/render/clip paths, and texture-ID generation skip GL calls during shutdown. Skipped frames do not call `swapBuffers()`.
- `check`/`checkStale` no longer throw on shutdown/context-loss errors; `drain` stops after three identical errors instead of printing 32 copies; `dumpState` prints `GL state unavailable` instead of null/zero state; and `beginFrame` resets the displayed step.

Expected force-close output is now one suppression line, plus a verbose frame-skip line only when that frame is included by the opt-in trace schedule:

```text
[MC3D-GL] flushToBuffer: emulator is shutting down; suppressing shutdown/context-loss GL errors and skipping GL work
[MC3D-GL] frame 251: flushToBuffer: skipped stack=4, target=graphics 240x320, transparent=false, clip=0,0 240x320, backCopied=true, step=skipped (shutdown/context loss)
```

If teardown is detected by context loss before the shutdown flag is visible, the first line instead says `GL context is no longer current`. Either way, no `RuntimeException`, stack trace, or repeated `1282` flood should be produced on force close. The original live-rendering diagnosis and the need for a non-force-closed runtime reproduction remain unchanged.

---

## 9. Live-frame log and trace throttling

A later log from a normal-running frame 60, followed by force close, confirms the shutdown guard and provides real GPU state:

- `vendor=ATI Technologies Inc.`, `renderer=AMD Radeon R5 M230 Series`, `version=4.6.14800 Compatibility Profile Context 23.11.1`.
- `maxTextureSize=16384`, `NPOT=true`, `pixelUnpackBuffer=0`, `unpackAlignment=1`, `unpackRowLength=0`, `unpackSkipRows=0`, `unpackSkipPixels=0`.
- The `copy2d` upload uses `GL_RGB`, `240x320`, `textureId=1`, active unit 1, and the expected `DirectByteBuffer[pos=76800, remaining=230400, capacity=307200]` tail slice. It reports `copy2d: texImage2D completed`, then `frame 60: flushToBuffer: complete`. There is **no GL error** in these live frames.
- The final `emulator is shutting down; suppressing shutdown/context-loss GL errors and skipping GL work` line is the expected one-line shutdown notice. No exception, stack trace, or `1282` flood follows.

This rules out NPOT, PBO, and leaked pixel-store state on this GPU/driver. It also shows why the earlier confidence notes called those clues, not proven causes.

The repeated `copy2d: enter` / `copy2d.texImage2D` lines after frame 60 were **trace noise**, not repeated failures. They come from `flushToBuffer()` — the JBlend `Graphics.flush()` entry point — refreshing the 2D backbuffer on later frames while `backCopied` remains true. The frame/state lines were already throttled to the first 30 frames and every 60th frame, but bind, `copy2d`, and upload-completion traces were still printed on every frame. Those high-frequency traces now use the same `frameTrace` schedule; upload parameters are still recorded in `lastTextureUpload` for error dumps. No rendering behavior was changed.

With the throttling applied, a frame such as 61–67 no longer emits three `copy2d` lines; only the first 30 frames and every 60th frame produce the verbose upload trace. Error reporting and shutdown suppression remain unconditional. A non-force-closed reproduction that actually shows the missing background is still needed to attribute the original `1282` on the affected setup.

---

## 10. Rendering fix for the missing background

This is the actual behavior change, not more logging. The goal is to ensure that `clearBG`'s colored far-plane quad is still rendered even when the 2D backbuffer-to-texture copy fails, and to remove the `ShowLogFrame=true` UI reentrancy that upstream issue #301 identified as a reproduction trigger.

### 10.1 GL uploads no longer poison or abort the frame

`GLDebug.prepareTextureUpload()` now runs on the GL thread immediately before both MC3D texture uploads:

- `Render.copy2d()` — the 2D backbuffer texture;
- `TextureImpl.loadToGL()` — game BMP textures.

It resets `GL_UNPACK_SWAP_BYTES`, `GL_UNPACK_LSB_FIRST`, `GL_UNPACK_ROW_LENGTH`, `GL_UNPACK_SKIP_ROWS`, `GL_UNPACK_SKIP_PIXELS`, and `GL_UNPACK_ALIGNMENT`; on GL 1.2+ it also resets `GL_UNPACK_IMAGE_HEIGHT`/`GL_UNPACK_SKIP_IMAGES`; on GL 1.5+ it unbinds `GL_PIXEL_UNPACK_BUFFER`. Any errors caused by the normalization itself are drained. This restores the alignment reset that `de47d2ee` had placed before `glTexImage2D` and later `40727e69` moved after it.

`GLDebug.checkStale()`, `GLDebug.checkNoThrow()`, and `Render.checkGlError()` now **drain and report errors without throwing**. Previously a sticky `GL_INVALID_OPERATION` from an earlier texture upload was observed in `copy2d(true)` and thrown as `RuntimeException("texImage2D: glError 1282")`, aborting `flushToBuffer()` before the queued background quad and `swapBuffers()`. Stale errors are now cleared before the current operation; live errors are reported with their correct operation label, but the frame continues.

### 10.2 `copy2d(true)` has a clear-color fallback

If `glTexImage2D` for the 2D backbuffer still fails, `copy2d()`:

1. logs the condition once with `GLDebug.reportOnce()`;
2. unbinds the failed backbuffer texture from unit 1;
3. clears the GL color and depth buffers to the game's `clearColor`;
4. clears the MIDP 2D backing buffer (respecting `mascotNo2DMixing`);
5. marks `backCopied = true` and returns **without throwing**.

`flushToBuffer()` then renders the queued 3D stack, including `clearBG`'s colored quad, and performs the normal readback/swap. On a later `copy2d(false)` failure it simply skips the stale 2D composite rather than erasing the finished 3D frame. After a successful `Program.Simple` draw, unit 0 is unbound so the backbuffer texture cannot be sampled where game textures belong; unit 1 remains the Simple program's sampler (`sampler0 = 1`).

### 10.3 No nested SWT event loop for the log window

`Log.createWindow()` used to run its own `while (!logShell.isDisposed()) readAndDispatch()` loop. With `ShowLogFrame=true`, that nested loop became the emulator's UI loop and could reenter paint/resize events while the M3G thread was binding its GL target. It now opens the log shell and returns; `EmulatorScreen.start()` owns the single SWT event loop. A dispose listener clears `logOpen`/`attachedToParent`, and the menu's `dispose()` still closes the window. This removes the config-dependent reproduction path from upstream #301 without requiring users to delete `property.txt`.

### 10.4 Validation and limits

The full desktop source set was compiled with JDK 8 / LWJGL / SWT classpath: **compile check passed** (exit 0, 9 pre-existing warnings). A no-GL reflection smoke test confirmed `checkStale()` does not throw, `checkNoThrow()` returns `GL_NO_ERROR`, `prepareTextureUpload()` returns `false` on a missing context, and `reportOnce()` emits one line. `git diff --check` passed.

These changes address the proven failure path: a failed 2D copy or stale GL error can no longer cancel the colored background quad, and the log-window reentrancy is gone. They are not a claim that the exact first GL command which sets `1282` on every affected GPU has been identified. No GL/X emulator run or freej2me-plus verification was performed; a real reproduction on the affected GPU with `MascotGLDebug=true` is still needed to confirm the visual result. Ask before running freej2me-plus.
