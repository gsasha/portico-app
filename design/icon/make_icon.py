"""Portico icon: one geometry, several outputs (Android vectors, SVG master, PNGs for HA's brand folder).

Everything is drawn in a 108 x 108 grid (Android's adaptive-icon canvas). The art stays inside the
66 x 66 centre safe zone (a circle of radius 33 around (54, 54)).
"""
import os
from PIL import Image, ImageDraw

OUT = os.path.dirname(os.path.abspath(__file__))
BG = "#1F4E8C"       # deep blue
FG = "#FFFFFF"       # white stone
ACCENT = "#F5B83D"   # lit doorway

# --- geometry (grid units) -------------------------------------------------------------------------
PEDIMENT = [(28, 46), (54, 26), (80, 46)]
RECTS = [  # x0, y0, x1, y1 : entablature, four columns, base slab
    (30, 48, 78, 53),
    (32.5, 55, 37.5, 71), (44.5, 55, 49.5, 71), (58.5, 55, 63.5, 71), (70.5, 55, 75.5, 71),
    (29, 71, 79, 76),
]
# doorway between the two inner columns: x 51.5..56.5, arched top
DOOR = dict(x0=51.5, x1=56.5, y_top=58, y_bot=71, r=2.5)


def door_path(d=DOOR):
    r = d["r"]
    return (f"M{d['x0']},{d['y_bot']} L{d['x0']},{d['y_top'] + r} "
            f"A{r},{r} 0 0 1 {d['x0'] + r},{d['y_top']} A{r},{r} 0 0 1 {d['x1']},{d['y_top'] + r} "
            f"L{d['x1']},{d['y_bot']} Z")


def path_poly(pts):
    return "M" + " L".join(f"{x:g},{y:g}" for x, y in pts) + " Z"


def path_rect(x0, y0, x1, y1):
    return path_poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


STONE_PATH = " ".join([path_poly(PEDIMENT)] + [path_rect(*r) for r in RECTS])


def write(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(text)


# --- Android vectors -------------------------------------------------------------------------------
write("ic_launcher_foreground.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Portico launcher icon, foreground: white portico with a lit doorway, inside the 66dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="{FG}" android:pathData="{STONE_PATH}" />
    <path android:fillColor="{ACCENT}" android:pathData="{door_path()}" />
</vector>
""")
write("ic_launcher_monochrome.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Portico themed (monochrome) icon: the same portico as one colour; the doorway is left as a gap. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="{STONE_PATH}" />
</vector>
""")
# notification small icon: 24dp, alpha only, scaled from the 108 grid art (art spans x 28..80, y 26..76)
S = 24 / 52.0
OX, OY = -28, -26


def tf(pts):
    return [((x + OX) * S, (y + OY) * S) for x, y in pts]


stat = [path_poly(tf(PEDIMENT))] + [path_poly(tf([(r[0], r[1]), (r[2], r[1]), (r[2], r[3]), (r[0], r[3])])) for r in RECTS]
write("ic_stat_portico.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Portico notification small icon: single colour (Android tints it); the doorway is a gap. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#FFFFFF" android:pathData="{' '.join(stat)}" />
</vector>
""")
write("ic_launcher_background.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z" />
</vector>
""")
write("ic_launcher.xml", """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")

# --- SVG master ------------------------------------------------------------------------------------
write("portico.svg", f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">
  <rect width="108" height="108" rx="22" fill="{BG}"/>
  <path fill="{FG}" d="{STONE_PATH}"/>
  <path fill="{ACCENT}" d="{door_path()}"/>
</svg>
""")


# --- PNGs (supersampled) -----------------------------------------------------------------------------
def render(size, rounded=True, scale_art=1.0, ss=4):
    n = size * ss
    k = n / 108.0
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if rounded:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(22 * k), fill=BG)
    else:
        d.rectangle([0, 0, n - 1, n - 1], fill=BG)
    c = 54.0

    def P(x, y):
        return ((c + (x - c) * scale_art) * k, (c + (y - c) * scale_art) * k)

    d.polygon([P(*p) for p in PEDIMENT], fill=FG)
    for x0, y0, x1, y1 in RECTS:
        d.rectangle([*P(x0, y0), *P(x1, y1)], fill=FG)
    # doorway: rectangle plus a semicircle on top
    dd = DOOR
    r = dd["r"]
    d.rectangle([*P(dd["x0"], dd["y_top"] + r), *P(dd["x1"], dd["y_bot"])], fill=ACCENT)
    d.pieslice([*P(dd["x0"], dd["y_top"]), *P(dd["x1"], dd["y_top"] + 2 * r)], 180, 360, fill=ACCENT)
    return im.resize((size, size), Image.LANCZOS)


# the art fills only the middle of the grid (it must survive launcher masks), so the brand icon
# enlarges it ~1.35x inside the rounded square to read at small sizes
render(256, scale_art=1.35).save(os.path.join(OUT, "icon.png"))
render(512, scale_art=1.35).save(os.path.join(OUT, "icon@2x.png"))
render(512, rounded=False, scale_art=1.0).save(os.path.join(OUT, "preview_launcher_square.png"))
# preview on a sheet: round / squircle / small
sheet = Image.new("RGBA", (1100, 340), (236, 240, 244, 255))
big = render(300, scale_art=1.0, rounded=False)
mask = Image.new("L", (300, 300), 0)
ImageDraw.Draw(mask).ellipse([0, 0, 299, 299], fill=255)
sheet.paste(big, (20, 20), mask)
sheet.paste(render(256, scale_art=1.35), (350, 40), render(256, scale_art=1.35))
for i, s in enumerate((96, 64, 48, 32)):
    im = render(s, scale_art=1.35)
    sheet.paste(im, (650 + i * 110, 40), im)
st = Image.new("RGBA", (24 * 8, 24 * 8), (0, 0, 0, 0))
sheet.save(os.path.join(OUT, "preview_sheet.png"))
print("ok", sorted(os.listdir(OUT)))
