"""Regenerate platform icons from the repository's icon_512.png (requires Pillow)."""
from pathlib import Path
from PIL import Image, ImageChops

root = Path(__file__).resolve().parent.parent
source = root / "icon_512.png"
icon = Image.open(source).convert("RGBA")
assert icon.size == (512, 512), "icon_512.png must be 512 x 512"
assets = root / "crates/csync-windows/assets"
assets.mkdir(parents=True, exist_ok=True)
icon.save(assets / "app.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
res = root / "android/app/src/main/res"
(res / "drawable-nodpi").mkdir(exist_ok=True)
icon.save(res / "drawable-nodpi/app_icon.png")
# Android status-bar icons require a transparent, monochrome alpha mask.
notification = Image.new("RGBA", icon.size, "white")
notification.putalpha(ImageChops.multiply(icon.convert("L"), icon.getchannel("A")))
notification.save(res / "drawable-nodpi/ic_notification.png")
for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = res / f"mipmap-{density}"
    folder.mkdir(exist_ok=True)
    icon.resize((size, size), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")
