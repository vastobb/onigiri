#!/usr/bin/env python3
"""
Compile-checks every GLSL shader in the mod without a GPU or a game client.

Minecraft compiles its own shaders at runtime, so a GLSL typo would otherwise
surface as a black screen on first launch with the driver log buried somewhere
unhelpful. This reproduces the same source the Java loader produces - header,
shared `#include` expansion, version-directive stripping, all of it - and hands
it to glslangValidator.

Run locally:  python3 tools/validate_shaders.py
CI runs it as part of the build, so a shader that fails to compile fails the
build instead of failing on a player's phone.

Note the ES 3.00 target. The mod is built for Android, where Minecraft reaches
GL through MobileGlues (GLES -> Vulkan); a desktop-profile shader will not
compile on a GLES driver. Validating the same dialect the phone runs is the
whole point - a `#version 330 core` check would pass while the mod stayed black.
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

# Must stay byte-identical to the headers in dev.onigiri.gl.ShaderProgram. If one
# changes the other must, or this stops testing what the game actually compiles.
# GLSL ES requires the version directive to be the very first token, and
# fragment shaders additionally require an explicit float precision.
FRAGMENT_HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"

VERTEX_HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

VERTEX_BODY = """out vec2 vUv;
void main() {
    int vid = gl_VertexID;
    vec2 corner = vec2(float((vid << 1) & 2), float(vid & 2));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
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


def strip_version(lines: list[str]) -> list[str]:
    """Drops a leading `#version` line; the generated header owns the version."""
    out: list[str] = []
    seen_content = False

    for line in lines:
        if not seen_content and line.strip().startswith("#version"):
            seen_content = True
            continue
        seen_content = True
        out.append(line)

    return out


def run_validator(validator: str, target: pathlib.Path) -> tuple[int, str]:
    result = subprocess.run([validator, str(target)], capture_output=True, text=True)
    return result.returncode, (result.stdout or result.stderr)


def main() -> int:
    validator = shutil.which("glslangValidator")

    if validator is None:
        # Fail loudly rather than silently passing: a build that skips shader
        # validation is a build that can ship a shader no driver will compile.
        print("ERROR glslangValidator not found (apt install glslang-tools)")
        return 1

    failures = 0
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="onigiri-shaders-"))

    try:
        vert = workdir / "fullscreen.vert"
        vert.write_text(VERTEX_HEADER + VERTEX_BODY)

        code, output = run_validator(validator, vert)
        print(f"{'ok  ' if code == 0 else 'FAIL'} fullscreen.vert (ES 3.00)")

        if code != 0:
            failures += 1
            print(output)

        for name in FRAGMENTS:
            source = SHADER_DIR / f"{name}.frag"

            if not source.exists():
                print(f"FAIL {name}.frag (missing)")
                failures += 1
                continue

            lines = strip_version(resolve_includes(source))
            target = workdir / f"{name}.frag"
            target.write_text(FRAGMENT_HEADER + "\n".join(lines) + "\n")

            code, output = run_validator(validator, target)
            print(f"{'ok  ' if code == 0 else 'FAIL'} {name}.frag (ES 3.00)")

            if code != 0:
                failures += 1
                print(output)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    if failures:
        print(f"\n{failures} shader(s) failed to compile")
        return 1

    print(f"\nall shaders compiled as GLSL ES 3.00 ({len(FRAGMENTS)} fragments + 1 vertex)")
    return 0


if __name__ == "__main__":
    sys.exit(main())