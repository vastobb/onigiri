# Onigiri

A custom screen-space renderer for **Minecraft 26.3** (Fabric), built for
**Android**.

Onigiri does not ship shadow maps. It marches the sun direction through the
depth buffer that Minecraft already produced, and it pays for that only on a
rotating subset of pixels per frame, relying on temporal reprojection to fill in
the rest. The result is softer contact shadows than a 1024px shadow map, at a
small fraction of the cost.

![pipeline](https://img.shields.io/badge/passes-6-6f42c1) ![minecraft](https://img.shields.io/badge/Minecraft-26.3-6f42c1) ![fabric](https://img.shields.io/badge/Fabric-0.19.5-6f42c1) ![java](https://img.shields.io/badge/Java-25-6f42c1) ![android](https://img.shields.io/badge/Android%20%2B%20MobileGlues-c05a10)

## Android support

This is the primary target, and it constrains the whole design.

Minecraft 26.3 reaches OpenGL through `SDL_GL_LoadLibrary` into
`org.lwjgl.opengl.GL`. On Android the library that call loads is supplied by
**MobileGlues**, which presents the **GLES** API and lowers it onto Vulkan. Two
consequences:

- **Every shader is GLSL ES 3.00** (`#version 300 es`). A desktop-profile shader
  will not compile on a GLES driver. ES 3.00 is also accepted by desktop GL
  3.2+, so the same source runs on both, and CI can validate the dialect that
  players actually execute rather than one no player runs.
- **No call newer than GL 3.1 / GLES 3.1 is used.** Render targets are allocated
  with `glTexImage2D`, not `glTexStorage2D` (GL 4.2 / GLES 3.1).

Fragment shaders declare `precision highp float` explicitly: GLSL ES has no
default float precision, and view-space positions in a large world do not
survive `mediump`.

CI validates the shaders as ES 3.00 and fails the build if a desktop-profile
`#version ... core` directive reappears in `common.glsl`.

The LWJGL build that ships with 26.3 contains `lwjgl-opengl` but **not**
`lwjgl-opengles`, so the mod uses the desktop GL bindings throughout. That is
correct on Android, because MobileGlues is what implements them.

## Why it is cheap

Three decisions carry the whole performance budget.

**Half resolution.** Ambient occlusion, reflections and shadows are all
low-frequency signals. Everything except the geometry pass and the final
composite runs at half res. On a tile-based mobile GPU this also cuts the number
of framebuffer tile switches per frame, which costs more than the shading does.

**Quarter-rate shadows.** Each frame, only a quarter of pixels trace a shadow
ray. The selection uses interleaved gradient noise offset per frame, which walks
the screen in a well-distributed order rather than scattering it randomly, so
there is no per-frame shimmer. The temporal pass reprojects and carries the
previous answer for the pixels that did not run.

**A geometry-free approach.** There is no shadow pass and no second geometry
pass. The only thing read from the game is the colour and depth buffers, which
also means nothing here breaks when a model or block format changes.

Per frame that works out to roughly:

```
full res:  geometry, composite
half res:  AO  (3 dirs x 3 taps)
           SSR (14 steps)
           shadow (8 steps, but only on 1/4 of pixels = ~2 effective)
           temporal x3 (9 taps each)
```

Those are the **Mobile** preset's numbers. **Reflections default to off**, since
SSR is the most expensive pass in the chain and a phone is better served by
shadows and AO that hold frame rate.

## The pipeline

| # | Pass | Res | What it does |
|---|------|-----|--------------|
| 1 | `geometry` | full | Rebuilds view-space normals and linear depth from the depth buffer alone. Uses the improved derivative scheme (closer of the forward/backward pair) to avoid stair-stepping on flat surfaces. |
| 2 | `ao` | half | Horizon-based occlusion. Marches a few screen-space slices per pixel and takes the highest neighbour above the tangent plane. |
| 3 | `ssr` | half | Reflections. Ray-marches the view-space reflection vector, re-projecting each step and comparing against depth. Misses fall back to an analytic sky rather than a hole. |
| 4 | `shadow` | half | Sun shadows. Marches toward the sun through the depth buffer. Surfaces facing away early-out for free. Only 25% of pixels per frame. |
| 5 | `temporal` | half | Reprojects history through last frame's matrices, validates it against a variance-derived neighbourhood box, then ping-pongs. Runs once per effect. |
| 6 | `composite` | full | Relights the frame: GGX specular, hemispheric ambient gated by AO, SSR replacing the ambient specular lobe, fog, ACES tonemap, vignette. |

AO and SSR write view depth into alpha, which lets the composite do a
**depth-aware upsample**. A plain bilinear fetch bleeds occlusion across
silhouettes and shows up as dark haloes around every object edge; weighting the
four taps by depth agreement keeps each effect pinned to its own surface.

## Lighting

The lighting model is written out in full rather than inherited:

- **GGX** normal distribution, **Smith** height-correlated visibility,
  **Schlick** fresnel, all in `common.glsl`.
- Specular is a **separate additive lobe**, not folded into the diffuse.
- **AO applies to ambient only, never to direct light.**
- **Smooth dawn and dusk**, so the horizon does not snap between lit and dark.
- The **moon takes over as the key light** at night, at a fraction of the
  intensity with a cool tint.
- Roughness is derived from local luminance, which keeps a cheap raymarch from
  looking like a mirror.

Vanilla bakes sky light into vertex colours during chunk building, so its own
lighting cannot respond to time of day without a full relight. Onigiri
recomputes sun direction and colour every frame instead, from the level's game
time.

## Requirements

- Minecraft **26.3**
- Fabric Loader **0.19.5+**
- Fabric API **0.162.0+26.3**
- Java **25**
- Android: a launcher providing **MobileGlues** (PojavLauncher, and others built
  on it)

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer) for 26.3.
2. Download the [Fabric API](https://modrinth.com/mod/fabric-api) jar.
3. Download the latest `onigiri-*.jar` from
   [Releases](../../releases) (the non-sources jar).
4. Drop both into `.minecraft/mods`.

## Controls

| Key | Action |
|-----|--------|
| `F6` | Open the settings menu |
| `F8` | Toggle the renderer |

### The settings menu

`F6` opens an in-game menu with no mixins involved. Everything applies
immediately rather than behind a Save button, so the effect of a change is
visible while the menu is still open:

- **Quality** - Potato / Mobile / Balanced / Ultra. Drives the sample counts.
- **Ambient occlusion**, **Shadows**, **Reflections** - on or off.
- **Half resolution** - leave on. Disabling it quadruples the cost of five of
  the six passes.
- **Resolution scale** - an extra `0.5x` or `0.75x` on top of half resolution.
  The single most effective knob on a weak GPU, and the most effective one to
  lower first if the frame rate is not holding.
- **Exposure** and **Temporal feedback** - `feedback` is the one worth tuning by
  hand. Higher is smoother but lags behind fast camera motion; below about
  `0.85` shadows trail on turns, above `0.95` they smear on moving entities.

The bottom line reports the effect buffer size the pipeline actually settled on,
which makes it obvious when a setting did not take effect.

`config/onigiri.json` is written on first launch and can be edited by hand.
Every value is clamped on load, so a mistyped entry cannot produce a broken
frame.

## Building

The build needs JDK 25. No Gradle wrapper is committed, so install Gradle 9.7 or
newer (Loom 1.18 requires it):

```sh
gradle build
```

The jar lands in `build/libs/`.

Shaders can be checked without a GPU or a running game:

```sh
# needs glslangValidator: apt install glslang-tools
python3 tools/validate_shaders.py
```

CI does both on every push and pull request.

## Design notes

**No mixins.** Onigiri hooks `LevelRenderEvents.END_MAIN`, which fires once the
world is drawn but before the GUI. That is the only point where both colour and
depth attachments are complete. Using the Fabric event avoids injecting into
`GameRenderer` internals that shift between versions.

**Reflection for the matrices, deliberately, and resolved once.** On 26.x the
projection matrix lives in a `ProjectionMatrixBuffer` and is never stored as a
readable field, and the view matrix is rebuilt per frame. `ProjectionModel`
reconstructs both from the camera position, rotation, FOV and aspect instead, and
caches the reflected `Method` objects - resolving them per frame meant
`getMethods()` was allocating a fresh copy of the entire method table twice a
frame. A renamed method degrades the effect; a mixin failure would crash the
client with an injection error. For a post-process renderer, failing soft is the
right trade.

**Forward and inverse matrices are built together, once.** This is the single
most important correctness property in the renderer. An earlier version called
`invert()` on the inverse matrices without ever assigning the forward matrices
into them, so `uInvProj`, `uInvViewProj` and `uView` all stayed at identity.
Every `viewPosFromDepth()` in the shaders then returned raw NDC, and AO, SSR,
shadows and the composite all operated on untransformed coordinates. Nothing
downstream could work. `ProjectionModel` now assigns each forward matrix before
inverting it, and `OnigiriPipeline` does not touch them at all.

**No `glGetError` in the render loop.** Reading the game's framebuffer
attachments happens twice per frame, and draining the error queue each time is
a synchronising call - on Adreno and Mali it flushes the command buffer and
costs a stall. On a phone that alone was enough to lose the frame.

**History is dropped on teleports.** Reprojection assumes a static world. After
a respawn or a teleport, last frame is meaningless, and blending against it
would smear the whole screen for a few frames.

**GL resources are released on `CLIENT_STOPPING`.** On Android the process can
outlive a session, so a leak accumulates across launches rather than being
reclaimed when the process finally exits.

## Status

The renderer compiles and the pipeline is complete, and the shaders are validated
as GLSL ES 3.00 in CI. It has **not** yet been run against a live client on a
physical Android device - CI verifies the build, not the visuals. Expect to want
some tuning of the defaults in `OnigiriConfig` once it is in front of real
gameplay on real hardware.

## License

MIT. See [LICENSE](LICENSE).