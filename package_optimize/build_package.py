#!/usr/bin/env python3
"""Build NEMESIS install package, DEX jar, and USB bundle for HKG_5W_Optimize."""
from __future__ import annotations

import hashlib
import math
import os
import shutil
import struct
import subprocess
import zipfile
import zlib
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
BUILD_DIR = BASE_DIR / "build"
CLASSES_DIR = BUILD_DIR / "classes"
DEX_DIR = BUILD_DIR / "dex"
SHORTCUT_CLASSES_DIR = BUILD_DIR / "shortcut_classes"
SHORTCUT_DEX_DIR = BUILD_DIR / "shortcut_dex"
PKG_STAGE_DIR = BUILD_DIR / "pkg_stage"
USB_ROOT_DIR = BUILD_DIR / "usb_root"

OUTER_ZIP_PATH = REPO_ROOT / "HKG_5W_Optimize_USB.zip"
OPT_VERSION = "1.0.2"


def find_javac() -> str:
    javac = shutil.which("javac")
    if javac:
        return javac
    candidates = [
        r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe",
        r"C:\Program Files\Android\Android Studio\jbr\bin\javac.exe",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    raise RuntimeError("javac not found in PATH or standard locations")


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    javac = find_javac()
    return str(Path(javac).with_name("java.exe" if os.name == "nt" else "java"))


def find_sdk_roots() -> list[str]:
    return [
        os.environ.get("ANDROID_HOME", ""),
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
        "/usr/local/lib/android/sdk",
        "/usr/lib/android-sdk",
    ]


def find_build_tool(names: tuple[str, ...]) -> str:
    for n in names:
        hit = shutil.which(n)
        if hit:
            return hit
    for root in find_sdk_roots():
        if not root or not os.path.isdir(root):
            continue
        bt_dir = Path(root) / "build-tools"
        if bt_dir.is_dir():
            for ver_dir in sorted(bt_dir.iterdir(), reverse=True):
                for n in names:
                    cand = ver_dir / n
                    if cand.is_file():
                        return str(cand)
    raise RuntimeError(f"Android SDK tool not found: {names}")


def find_d8() -> str:
    return find_build_tool(("d8.bat", "d8"))


def find_aapt() -> str:
    return find_build_tool(("aapt.exe", "aapt"))


def find_apksigner() -> str:
    return find_build_tool(("apksigner.bat", "apksigner"))


def find_android_jar() -> str:
    for root in find_sdk_roots():
        if not root or not os.path.isdir(root):
            continue
        plat_dir = Path(root) / "platforms"
        if plat_dir.is_dir():
            for ver_dir in sorted(plat_dir.iterdir(), reverse=True):
                cand = ver_dir / "android.jar"
                if cand.is_file():
                    return str(cand)
    raise RuntimeError("Android SDK android.jar not found")


def find_keytool() -> str:
    kt = shutil.which("keytool")
    if kt:
        return kt
    javac = find_javac()
    cand = Path(javac).with_name("keytool.exe" if os.name == "nt" else "keytool")
    if cand.is_file():
        return str(cand)
    raise RuntimeError("keytool not found alongside javac")


def png_chunk(tag: bytes, payload: bytes) -> bytes:
    crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)


def render_tile_icon_png(
    bg_top: tuple[int, int, int],
    bg_bot: tuple[int, int, int],
    border_col: tuple[int, int, int],
    arc_left: tuple[int, int, int],
    arc_right: tuple[int, int, int],
    needle_col: tuple[int, int, int],
    bolt_col: tuple[int, int, int],
) -> bytes:
    """Render a 99x99 RGBA PNG launcher tile icon using pure Python + 2x2 SSAA."""
    w, h = 99, 99
    cx, cy = 49.5, 49.5
    bx, by, br = 43.5, 43.5, 22.0
    gcx, gcy = 49.5, 53.0
    ang_rad = math.radians(-32.0)
    nx0, ny0 = gcx, gcy
    nx1, ny1 = gcx + math.cos(ang_rad) * 24.5, gcy + math.sin(ang_rad) * 24.5
    ndx, ndy = nx1 - nx0, ny1 - ny0
    nlen2 = ndx * ndx + ndy * ndy

    bolt_tris = [
        ((51.5, 60.0), (43.5, 70.0), (49.5, 70.0)),
        ((49.5, 68.5), (55.5, 68.5), (47.5, 79.0)),
    ]

    def pt_in_tri(px: float, py: float, tri: tuple[tuple[float, float], ...]) -> bool:
        (ax, ay), (bxp, byp), (cxp, cyp) = tri
        v0x, v0y = cxp - ax, cyp - ay
        v1x, v1y = bxp - ax, byp - ay
        v2x, v2y = px - ax, py - ay
        dot00 = v0x * v0x + v0y * v0y
        dot01 = v0x * v1x + v0y * v1y
        dot02 = v0x * v2x + v0y * v2y
        dot11 = v1x * v1x + v1y * v1y
        dot12 = v1x * v2x + v1y * v2y
        inv = 1.0 / (dot00 * dot11 - dot01 * dot01)
        u = (dot11 * dot02 - dot01 * dot12) * inv
        v = (dot00 * dot12 - dot01 * dot02) * inv
        return u >= 0.0 and v >= 0.0 and (u + v) <= 1.0

    def sample(px: float, py: float) -> tuple[int, int, int, int]:
        dx = max(abs(px - cx) - (bx - br), 0.0)
        dy = max(abs(py - cy) - (by - br), 0.0)
        dist = math.hypot(dx, dy) + min(max(abs(px - cx) - (bx - br), abs(py - cy) - (by - br)), 0.0) - br
        if dist > 0.0:
            return (0, 0, 0, 0)

        t = max(0.0, min(1.0, (py - 6.0) / 87.0))
        r = int(bg_top[0] + (bg_bot[0] - bg_top[0]) * t)
        g = int(bg_top[1] + (bg_bot[1] - bg_top[1]) * t)
        b = int(bg_top[2] + (bg_bot[2] - bg_top[2]) * t)

        if dist >= -2.4:
            r, g, b = border_col

        gdx, gdy = px - gcx, py - gcy
        gr = math.hypot(gdx, gdy)
        if 21.0 <= gr <= 29.0:
            deg = math.degrees(math.atan2(gdy, gdx))
            if deg < 35.0:
                deg += 360.0
            # Sweep 145 deg -> 395 deg (i.e. bottom-left clockwise across top to bottom-right)
            if 145.0 <= deg <= 395.0:
                if deg >= 315.0:
                    r, g, b = arc_right
                else:
                    r, g, b = arc_left

        # Needle segment distance
        proj = max(0.0, min(1.0, ((px - nx0) * ndx + (py - ny0) * ndy) / nlen2))
        ndist = math.hypot(px - (nx0 + proj * ndx), py - (ny0 + proj * ndy))
        if ndist <= 2.3 or gr <= 5.5:
            r, g, b = needle_col

        if pt_in_tri(px, py, bolt_tris[0]) or pt_in_tri(px, py, bolt_tris[1]):
            r, g, b = bolt_col

        return (r, g, b, 255)

    rows = bytearray()
    offsets = (0.25, 0.75)
    for y in range(h):
        rows.append(0)
        for x in range(w):
            sr = sg = sb = sa = 0
            for ox in offsets:
                for oy in offsets:
                    cr, cg, cb, ca = sample(x + ox, y + oy)
                    sr += cr
                    sg += cg
                    sb += cb
                    sa += ca
            rows.extend((sr >> 2, sg >> 2, sb >> 2, sa >> 2))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + png_chunk(b"IEND", b"")
    )


def build_shortcut_apk(javac: str, d8: str) -> Path:
    android_jar = find_android_jar()
    aapt = find_aapt()
    apksigner = find_apksigner()
    keytool = find_keytool()

    SHORTCUT_CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    SHORTCUT_DEX_DIR.mkdir(parents=True, exist_ok=True)

    launch_java = BASE_DIR / "shortcut_app" / "src" / "com" / "hkg" / "optimize" / "app" / "LaunchActivity.java"
    manifest_xml = BASE_DIR / "shortcut_app" / "AndroidManifest.xml"
    subprocess.run(
        [
            javac,
            "--release",
            "8",
            "-encoding",
            "UTF-8",
            "-cp",
            android_jar,
            "-d",
            str(SHORTCUT_CLASSES_DIR),
            str(launch_java),
        ],
        check=True,
    )

    class_files = [str(p) for p in sorted(SHORTCUT_CLASSES_DIR.rglob("*.class"))]
    if not class_files:
        raise RuntimeError("No shortcut classes compiled")
    subprocess.run(
        [
            d8,
            "--min-api",
            "19",
            "--no-desugaring",
            "--lib",
            android_jar,
            "--output",
            str(SHORTCUT_DEX_DIR),
            *class_files,
        ],
        check=True,
    )

    dex_bytes = (SHORTCUT_DEX_DIR / "classes.dex").read_bytes()
    if dex_bytes[:8] != b"dex\n035\x00":
        raise RuntimeError(f"Unexpected shortcut DEX magic: {dex_bytes[:8]!r}")

    shortcut_res_dir = BUILD_DIR / "shortcut_res"
    shortcut_res_drawable = shortcut_res_dir / "drawable"
    shortcut_res_drawable.mkdir(parents=True, exist_ok=True)
    (shortcut_res_drawable / "ic_launcher.png").write_bytes(
        render_tile_icon_png(
            bg_top=(38, 52, 74),
            bg_bot=(21, 30, 46),
            border_col=(56, 189, 248),
            arc_left=(56, 189, 248),
            arc_right=(16, 185, 129),
            needle_col=(248, 250, 252),
            bolt_col=(250, 204, 21),
        )
    )

    unsigned_apk = BUILD_DIR / "HkgOptimizeShortcut-unsigned.apk"
    subprocess.run(
        [
            aapt,
            "package",
            "-f",
            "-M",
            str(manifest_xml),
            "-S",
            str(shortcut_res_dir),
            "-I",
            android_jar,
            "-F",
            str(unsigned_apk),
        ],
        check=True,
    )
    with zipfile.ZipFile(unsigned_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_bytes)

    keystore = BUILD_DIR / "shortcut_sign.p12"
    if keystore.exists():
        keystore.unlink()
    subprocess.run(
        [
            keytool,
            "-genkeypair",
            "-storetype",
            "PKCS12",
            "-keystore",
            str(keystore),
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "hkgopt",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=HKGOptimize,O=HKG,C=KR",
        ],
        check=True,
    )

    signed_apk = BUILD_DIR / "HkgOptimizeShortcut.apk"
    subprocess.run(
        [
            apksigner,
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "false",
            "--v3-signing-enabled",
            "false",
            "--min-sdk-version",
            "19",
            "--out",
            str(signed_apk),
            str(unsigned_apk),
        ],
        check=True,
    )
    return signed_apk


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_lf(path: Path) -> bytes:
    raw = path.read_bytes().replace(b"\r\n", b"\n")
    path.write_bytes(raw)
    return raw


def build() -> None:
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    DEX_DIR.mkdir(parents=True, exist_ok=True)
    PKG_STAGE_DIR.mkdir(parents=True, exist_ok=True)
    USB_ROOT_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Normalize LF line endings on all package text/script files
    source_files = [
        "NEMESIS.data",
        "config/optimize.conf",
        "scripts/post.sh",
        "scripts/preun.sh",
        "bin/launch.sh",
        "svc/boot-apply.sh",
        "hmgapi_mod/mod.json",
        "hmgapi_mod/bin/open-ui.sh",
    ]
    for rel in source_files:
        normalize_lf(BASE_DIR / rel)
    normalize_lf(BASE_DIR / "shortcut_app" / "AndroidManifest.xml")

    # 2. Compile Java sources (Java 8 target with Java 7 constructs -> API 19 Dalvik DEX)
    javac = find_javac()
    java = find_java()
    d8 = find_d8()
    java_srcs = [str(p) for p in sorted((BASE_DIR / "src_java").rglob("*.java"))]
    if not java_srcs:
        raise RuntimeError("No Java source files found under src_java")

    subprocess.run(
        [javac, "--release", "8", "-encoding", "UTF-8", "-d", str(CLASSES_DIR), *java_srcs],
        check=True,
    )

    # 3. Run host self-test suite on compiled classes
    subprocess.run(
        [java, "-cp", str(CLASSES_DIR), "com.hkg.optimize.OptimizeMain", "test-self"],
        check=True,
    )

    # 4. Convert .class files to Dalvik DEX (--min-api 19 --no-desugaring -> DEX 035)
    app_classes_dir = CLASSES_DIR / "com" / "hkg" / "optimize"
    class_files = [str(p) for p in sorted(app_classes_dir.rglob("*.class"))]
    subprocess.run(
        [d8, "--min-api", "19", "--no-desugaring", "--output", str(DEX_DIR), *class_files],
        check=True,
    )

    classes_dex = DEX_DIR / "classes.dex"
    dex_bytes = classes_dex.read_bytes()
    if dex_bytes[:8] != b"dex\n035\x00":
        raise RuntimeError(f"Unexpected DEX magic header: {dex_bytes[:8]!r} (expected b'dex\\n035\\x00')")

    jar_path = BUILD_DIR / "optimize.jar"
    with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_bytes)

    jar_sha = sha256_bytes(jar_path.read_bytes())
    (BUILD_DIR / "optimize.jar.sha256").write_bytes(f"{jar_sha}  optimize.jar\n".encode("utf-8"))

    # 5. Stage NEMESIS package files
    for rel in source_files:
        src = BASE_DIR / rel
        dst = PKG_STAGE_DIR / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    stage_jar = PKG_STAGE_DIR / "bin" / "optimize.jar"
    stage_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_path, stage_jar)

    # 5b. Build & stage HMG-API companion shortcut APK + 99x99 tile PNG icons
    shortcut_apk = build_shortcut_apk(javac, d8)
    stage_mod_dir = PKG_STAGE_DIR / "hmgapi_mod"
    stage_mod_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(shortcut_apk, stage_mod_dir / "HkgOptimizeShortcut.apk")
    (stage_mod_dir / "icon.png").write_bytes(
        render_tile_icon_png(
            bg_top=(38, 52, 74),
            bg_bot=(21, 30, 46),
            border_col=(56, 189, 248),
            arc_left=(56, 189, 248),
            arc_right=(16, 185, 129),
            needle_col=(248, 250, 252),
            bolt_col=(250, 204, 21),
        )
    )
    (stage_mod_dir / "icon_night.png").write_bytes(
        render_tile_icon_png(
            bg_top=(18, 28, 46),
            bg_bot=(10, 16, 28),
            border_col=(16, 185, 129),
            arc_left=(56, 189, 248),
            arc_right=(52, 211, 153),
            needle_col=(241, 245, 249),
            bolt_col=(250, 204, 21),
        )
    )
    (stage_mod_dir / "icon_disabled.png").write_bytes(
        render_tile_icon_png(
            bg_top=(105, 114, 128),
            bg_bot=(78, 86, 98),
            border_col=(148, 163, 184),
            arc_left=(180, 188, 198),
            arc_right=(200, 208, 216),
            needle_col=(226, 232, 240),
            bolt_col=(203, 213, 225),
        )
    )
    (stage_mod_dir / "icon_disabled_night.png").write_bytes(
        render_tile_icon_png(
            bg_top=(34, 40, 50),
            bg_bot=(22, 26, 34),
            border_col=(71, 85, 105),
            arc_left=(100, 116, 139),
            arc_right=(120, 135, 155),
            needle_col=(148, 163, 184),
            bolt_col=(148, 163, 184),
        )
    )

    # 6. Build inner NEMESIS package zip (packages/hkg.optimize.zip)
    inner_zip_path = USB_ROOT_DIR / "packages" / "hkg.optimize.zip"
    inner_zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(inner_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(PKG_STAGE_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(PKG_STAGE_DIR).as_posix()
                zinfo = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if arcname.endswith(".sh"):
                    zinfo.external_attr = (0o100755 & 0xFFFF) << 16
                else:
                    zinfo.external_attr = (0o100644 & 0xFFFF) << 16
                zf.writestr(zinfo, file_path.read_bytes())

    # 7. Build USB root descriptor (usb_root/NEMESIS.data)
    usb_meta = (
        "[nemesis]\n"
        "version=1.0\n"
        "type=install\n"
        "path=packages/hkg.optimize.zip\n"
    )
    (USB_ROOT_DIR / "NEMESIS.data").write_bytes(usb_meta.encode("utf-8"))

    # 8. Build outer USB bundle zip (HKG_5W_Optimize_USB.zip)
    target_zip = OUTER_ZIP_PATH
    try:
        if target_zip.exists():
            os.chmod(target_zip, 0o666)
            target_zip.unlink()
    except PermissionError:
        target_zip = REPO_ROOT / f"HKG_5W_Optimize_USB_v{OPT_VERSION}.zip"
    with zipfile.ZipFile(target_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(USB_ROOT_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(USB_ROOT_DIR).as_posix()
                zf.write(file_path, arcname)

    print(f"Built DEX jar:       {jar_path} ({jar_path.stat().st_size} bytes, sha256={jar_sha[:16]}...)")
    print(f"Built inner package: {inner_zip_path} ({inner_zip_path.stat().st_size} bytes)")
    print(f"Built USB bundle:    {target_zip} ({target_zip.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
