"""Makes Cosmotune's placeholder icons: a violet-to-pink square with a white C.

    python tools/make-icons.py
Needs ffmpeg on PATH (it draws the picture); the .icns file is written here without any tool.
The font path below is the Windows one and must be edited on other systems.
"""
import struct
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "desktopApp" / "packaging" / "icons"
FONT = "C\\:/Windows/Fonts/arialbd.ttf"


def draw(size: int, out: Path) -> None:
    # The diagonal is computed with geq, not the gradients source: that source dithers with random noise (every run wrote
    # different files) and its colour ramp changes length with the picture size. #7c3aed = (124, 58, 237), #ec4899 = (236, 72, 153).
    t = "(X+Y)/(2*(W-1))"
    graph = (
        f"color=c=black:s={size}x{size}:d=1,format=gbrp,"
        f"geq=r='124+112*{t}':g='58+14*{t}':b='237-84*{t}',"
        f"drawtext=fontfile='{FONT}':text='C':fontsize={int(size * 0.62)}:fontcolor=white:"
        f"x=(w-text_w)/2:y=(h-text_h)/2"
    )
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", graph, "-frames:v", "1", str(out)], check=True)


def icns(png_by_type: dict, out: Path) -> None:
    body = b"".join(t + struct.pack(">I", len(d) + 8) + d for t, d in png_by_type.items())
    out.write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)


def main() -> None:
    ICONS.mkdir(parents=True, exist_ok=True)
    draw(1024, ICONS / "AppIcon.png")
    draw(512, ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png")
    # Windows icon: one 256 px image is enough for the installer and the taskbar
    draw(256, ICONS / "_256.png")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(ICONS / "_256.png"), str(ICONS / "AppIcon.ico")], check=True)
    sizes = {b"ic07": 128, b"ic08": 256, b"ic09": 512, b"ic10": 1024}
    data = {}
    for t, s in sizes.items():
        tmp = ICONS / f"_{s}.png"
        draw(s, tmp)
        data[t] = tmp.read_bytes()
    icns(data, ICONS / "AppIcon.icns")
    for f in ICONS.glob("_*.png"):
        f.unlink()
    print("icons written to", ICONS)


if __name__ == "__main__":
    main()
