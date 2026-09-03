import os

try:
    from PIL import Image, ImageDraw
except ImportError:
    os.system("pip install Pillow")
    from PIL import Image, ImageDraw

ICON_SIZES = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

THEMES = [
    "whatsapp",
    "instagram",
    "calculator",
    "chrome",
    "spotify",
    "tiktok",
    "youtube",
    "telegram",
]

def load_img(path):
    img = Image.open(path)
    if img.mode == "RGBA":
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.split()[3])
        return bg
    return img.convert("RGB")

# ── App Icon ──────────────────────────────────────────
print("=== Generating App Icons ===")
use_icon = os.path.isfile("icon.jpg")

if use_icon:
    img_base = Image.open("icon.jpg")
    if img_base.mode == "RGBA":
        bg = Image.new("RGB", img_base.size, (0, 0, 0))
        bg.paste(img_base, mask=img_base.split()[3])
        img_base = bg
    elif img_base.mode != "RGB":
        img_base = img_base.convert("RGB")

for density, size in ICON_SIZES.items():
    out_dir = os.path.join("android", "app", "src", "main", "res", f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)

    if use_icon:
        icon = img_base.resize((size, size), Image.LANCZOS)
    else:
        icon = Image.new("RGB", (size, size), (8, 12, 16))
        draw = ImageDraw.Draw(icon)
        draw.ellipse(
            [size // 4, size // 4, 3 * size // 4, 3 * size // 4],
            fill=(0, 212, 255)
        )

    icon.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    icon.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print(f"  [{density}] ic_launcher.png {size}x{size}")

print("App Icons Done\n")

# ── Theme Icons ───────────────────────────────────────
print("=== Generating Theme Icons ===")

for theme in THEMES:
    src = None
    for ext in ["jpg", "jpeg", "png", "webp"]:
        candidate = f"{theme}.{ext}"
        if os.path.isfile(candidate):
            src = candidate
            break

    if not src:
        print(f"  [SKIP] {theme} — file tidak ditemukan")
        continue

    img_base = load_img(src)

    for density, size in ICON_SIZES.items():
        out_dir = os.path.join("android", "app", "src", "main", "res", f"drawable-{density}")
        os.makedirs(out_dir, exist_ok=True)
        icon = img_base.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(out_dir, f"theme_{theme}.png"), "PNG")

    print(f"  [OK] {theme} → theme_{theme}.png")

print("Theme Icons Done\n")
print("=== All Done ===")
