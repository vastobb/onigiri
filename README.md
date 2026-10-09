# Onigiri

A custom screen-space renderer for **Minecraft 26.3** (Fabric).

Onigiri does not ship shadow maps. It marches the sun direction through the
depth buffer that Minecraft already produced, and it pays for that only on a
rotating subset of pixels per frame, relying on temporal reprojection to fill in
the rest. The result is softer contact shadows than a 1024px shadow map, at a
small fraction of the cost.

![pipeline](https://img.shields.io/badge/passes-6-6f42c1) ![minecraft](https://img.shields.io/badge/Minecraft-26.3-6f42c1) ![fabric](https://img.shields.io/badge/Fabric-0.19.5-6f42c1) ![java](https://img.shields.io/badge/Java-25-6f42c1)

## Why it is cheap

Three decisions carry the whole performance budget.

**Half resolution.** Ambient occlusion, reflections and shadows are all
low-frequency signals. Everything except the geometry pass and the final
composite runs at half res.

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
full res:  geometry (cheap), composite (cheap)
half res:  AO  (4 dirs x 5 taps)
           SSR (28 steps)
           shadow (14 steps, but only on 1/4 of pixels = ~3.5 effective)
           temporal x3 (9 taps each)
```

Roughly **half the sample count of a single 1024px shadow map**, plus occlusion
and reflections on top.

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
- Specular is a **separate additive lobe**, not folded into the diffuse. Folding
  it in makes highlights look like tinted paint rather than light.
- **AO applies to ambient only, never to direct light.** Applying it to both is
  the most common AO mistake, and it flattens every direct highlight.
- **Smooth dawn and dusk**, so the horizon does not snap between lit and dark.
- The **moon takes over as the key light** at night, at a fraction of the
  intensity with a cool tint, rather than the scene simply going black.
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

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer) for 26.3.
2. Download the [Fabric API](https://modrinth.com/mod/fabric-api) jar.
3. Download the latest `onigiri-*.jar` from
   [Releases](../../releases) (the non-sources jar).
4. Drop both into `.minecraft/mods`.

## Controls

| Key | Action |
|-----|--------|
| `F8` | Toggle the renderer |
| `F9` | Toggle debug stats |

## Configuration

`config/onigiri.json` is written on first launch. Edit it and restart.

```json
{
  "quality": 2,
  "halfResolution": true,
  "ambientOcclusion": true,
  "reflections": true,
  "shadows": true,
  "aoStrength": 0.85,
  "ssrStrength": 1.0,
  "shadowStrength": 0.9,
  "specularStrength": 0.6,
  "exposure": 1.05,
  "temporalFeedback": 0.92
}
```

`quality` is `0` potato through `3` ultra, and drives the sample counts:

| quality | AO dirs x steps | SSR steps | shadow steps |
|---------|----------------|-----------|--------------|
| 0 | 2 x 2 | 12 | 6 |
| 1 | 3 x 4 | 20 | 10 |
| 2 (default) | 4 x 5 | 28 | 14 |
| 3 | 6 x 8 | 40 | 20 |

`temporalFeedback` is the one value worth tuning. Higher is smoother but lags
more behind fast camera motion. Below about `0.85` shadows start to trail on
turns; above `0.95` they smear on moving entities.

## Building

The build needs JDK 25. No Gradle wrapper is committed, so install Gradle 9.7 or
newer (Loom 1.18 requires it):

```sh
gradle build
```

The jar lands in `build/libs/`.

CI runs on every push and pull request and attaches each successful build to a
draft release.

## Design notes

**No mixins.** Onigiri hooks `LevelRenderEvents.END_MAIN`, which fires once the
world is drawn but before the GUI. That is the only point where both colour and
depth attachments are complete. Using the Fabric event avoids injecting into
`GameRenderer` internals that shift between versions.

**Reflection for the matrices, deliberately.** On 26.x the projection matrix
lives in a `ProjectionMatrixBuffer` and is never stored as a readable field, and
the view matrix is rebuilt per frame. `ProjectionModel` reconstructs both from
the camera position, rotation, FOV and aspect instead. A renamed method degrades
the effect; a mixin failure would crash the client with an injection error. For a
post-process renderer, failing soft is the right trade.

**History is dropped on teleports.** Reprojection assumes a static world. After a
respawn or a teleport, last frame is meaningless, and blending against it would
smear the whole screen for a few frames.

## Status

The renderer compiles and the pipeline is complete. It has not yet been run
against a live client — CI verifies the build, not the visuals. Expect to want
some tuning of the defaults in `OnigiriConfig` once it is in front of real
gameplay.

## License

MIT. See [LICENSE](LICENSE).
