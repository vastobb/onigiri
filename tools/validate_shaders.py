#!/usr/bin/env python3
"""
Compile-checks every GLSL shader in the mod without a GPU or a game client.

Minecraft compiles its own shaders at runtime, so a GLSL typo would otherwise
surface as a black screen on first launch with the driver log buried somewhere
unhelpful. This reproduces the same source the Java loader produces - header,
shared `#include` expansion, all of it - and hands it to glslangValidator.

Run locally:  python3 tools/validate_shaders.py
CI runs it as part of the build, so a shader that fails to compile fails the
build instead of failing in-game.
"""

import re
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHADER_DIR = ROOT / "src/client/resources/assets/onigiri/shaders"

FRAGMENTS = ["geometry", "ao", "ssr", "shadow", "temporal", "composite"]

# Kept byte-identical to VERTEX_BODY in dev.onigiri.gl.ShaderProgram. If one
# changes the other must, or this stops testing what the game actually compiles.
VERTEX = """#version 150 core

out vec2 vUv;

void main() {
    vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = c;
    gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}
"""


def resolve_includes(path: pathlib.Path, depth: int = 0) -> list[str]:
    """Expands `#include "file"` directives the same way the Java loader does."""
    if depth > 8:
        raise RuntimeError(f"include nesting too deep in {path}")

    lines: list[str] = []

    for line in path.read_text().splitlines():
        stripped = line.strip()

        if stripped.startswith("#include"):
            match = re.search(r'"([^"]+)"', stripped)
            if not match:
                raise RuntimeError(f"malformed include in {path}: {stripped}")
            lines.extend(resolve_includes(path.parent / match.group(1), depth + 1))
        else:
            lines.append(line)

    return lines


def main() -> int:
    validator = shutil.which("glslangValidator")

    if validator is None:
        print("glslangValidator not found, skipping shader validation")
        return 0

    failures = 0
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="onigiri-shaders-"))

    try:
        vert = workdir / "fullscreen.vert"
        vert.write_text(VERTEX)

        result = subprocess.run([validator, str(vert)], capture_output=True, text=True)
        print(f"{'ok  ' if result.returncode == 0 else 'FAIL'} fullscreen.vert")

        if result.returncode != 0:
            failures += 1
            print(result.stdout or result.stderr)

        for name in FRAGMENTS:
            source = SHADER_DIR / f"{name}.frag"

            if not source.exists():
                print(f"FAIL {name}.frag (missing)")
                failures += 1
                continue

            lines = [
                line
                for line in resolve_includes(source)
                if not line.strip().startswith("#version")
            ]

            target = workdir / f"{name}.frag"
            target.write_text("#version 150 core\n" + "\n".join(lines) + "\n")

            result = subprocess.run([validator, str(target)], capture_output=True, text=True)
            print(f"{'ok  ' if result.returncode == 0 else 'FAIL'} {name}.frag")

            if result.returncode != 0:
                failures += 1
                print(result.stdout or result.stderr)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    if failures:
        print(f"\n{failures} shader(s) failed to compile")
        return 1

    print(f"\nall shaders compiled ({len(FRAGMENTS)} fragments + 1 vertex)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
