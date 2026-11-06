from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageColor, ImageDraw, ImageFont

W, H = 1600, 900
SCALE = 2
OUT = Path(__file__).with_name("gauge-preview.png")

C = {
    "canvas": "#efefed",
    "screen": "#ffffff",
    "text": "#111111",
    "secondary": "#5a5a5a",
    "muted": "#8c8c8c",
    "border": "#d9d9d9",
    "track": "#f2f2f2",
    "primary": "#111111",
    "warning": "#9a6700",
    "error": "#b3261e",
    "green": "#00c853",
    "yellow": "#ffc107",
    "red": "#e53935",
}

image = Image.new("RGB", (W * SCALE, H * SCALE), C["canvas"])
draw = ImageDraw.Draw(image)
REGULAR = r"C:\Windows\Fonts\msyh.ttc"
BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
_font_cache: dict[tuple[int, bool], ImageFont.FreeTypeFont] = {}


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    key = (size, bold)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(BOLD if bold else REGULAR, size * SCALE)
    return _font_cache[key]


def coords(values):
    return tuple(round(v * SCALE) for v in values)


def text(value, x, y, size=14, color=None, align="left", bold=False):
    anchor = {"left": "lm", "center": "mm", "right": "rm"}[align]
    draw.text(
        coords((x, y)),
        value,
        font=font(size, bold),
        fill=color or C["text"],
        anchor=anchor,
    )


def rounded_rect(x, y, w, h, radius=18, fill=None, outline=None, width=1):
    draw.rounded_rectangle(
        coords((x, y, x + w, y + h)),
        radius=radius * SCALE,
        fill=fill,
        outline=outline,
        width=width * SCALE,
    )


def line(x1, y1, x2, y2, color=None, width=1):
    draw.line(coords((x1, y1, x2, y2)), fill=color or C["border"], width=width * SCALE)


def arc_points(cx, cy, radius, start, end, steps=100):
    count = max(2, round(steps * abs(end - start) / 270))
    return [
        coords((
            cx + radius * math.cos(math.radians(start + (end - start) * i / (count - 1))),
            cy + radius * math.sin(math.radians(start + (end - start) * i / (count - 1))),
        ))
        for i in range(count)
    ]


def round_arc(cx, cy, radius, start, end, color, width):
    points = arc_points(cx, cy, radius, start, end)
    draw.line(points, fill=color, width=width * SCALE, joint="curve")
    cap_radius = width * SCALE / 2
    for px, py in (points[0], points[-1]):
        draw.ellipse(
            (round(px - cap_radius), round(py - cap_radius), round(px + cap_radius), round(py + cap_radius)),
            fill=color,
        )


def gradient_color(stops, position):
    position = max(0.0, min(1.0, position))
    for (left_pos, left_color), (right_pos, right_color) in zip(stops, stops[1:]):
        if position <= right_pos:
            amount = (position - left_pos) / max(0.000001, right_pos - left_pos)
            a = ImageColor.getrgb(left_color)
            b = ImageColor.getrgb(right_color)
            return tuple(round(a[i] + (b[i] - a[i]) * amount) for i in range(3))
    return ImageColor.getrgb(stops[-1][1])


def gradient_arc(cx, cy, radius, start, end, stops, width):
    segments = max(2, math.ceil((end - start) / 2))
    for i in range(segments):
        a0 = start + (end - start) * i / segments
        a1 = start + (end - start) * (i + 1) / segments
        position = ((a0 + a1) / 2 - 135) / 270
        draw.line(
            arc_points(cx, cy, radius, a0, a1, steps=2),
            fill=gradient_color(stops, position),
            width=width * SCALE,
        )
    cap_radius = width * SCALE / 2
    for angle, position in ((start, 0.0), (end, (end - 135) / 270)):
        px, py = arc_points(cx, cy, radius, angle, angle, steps=2)[0]
        draw.ellipse(
            (round(px - cap_radius), round(py - cap_radius), round(px + cap_radius), round(py + cap_radius)),
            fill=gradient_color(stops, position),
        )


def gauge_stops(percent, is_balance):
    if is_balance:
        return ((0.0, C["red"]), (0.5, C["yellow"]), (1.0, C["green"]))
    return ((0.0, C["green"]), (0.5, C["yellow"]), (1.0, C["red"]))


def gauge(cx, cy, radius, percent, balance=None):
    capped = max(0.0, min(100.0, percent))
    width = 10 if balance is not None else 9
    round_arc(cx, cy, radius, 135, 405, C["track"], width)
    if capped > 0:
        gradient_arc(
            cx, cy, radius, 135, 135 + 270 * capped / 100,
            gauge_stops(capped, balance is not None), width,
        )
    if balance is not None:
        text(balance, cx, cy - 7, 18, bold=True, align="center")
        text(f"{capped:g}%", cx, cy + 16, 11, C["secondary"], align="center")
    else:
        text(f"{capped:g}%", cx, cy, 17, bold=True, align="center")


def card(x, y, w, h, name, tag):
    rounded_rect(x + 1, y + 3, w, h, 18, "#e8e8e6")
    rounded_rect(x, y, w, h, 18, C["screen"], C["border"])
    text(name, x + 18, y + 24, 15, bold=True)
    text(tag, x + w - 18, y + 24, 10, C["secondary"], align="right")


def dual_card(x, y, w, h, name, tag, first, second):
    card(x, y, w, h, name, tag)
    for item, cx in zip((first, second), (x + w * 0.27, x + w * 0.73)):
        gauge(cx, y + 88, 35, item[0])
        text(item[1], cx, y + 141, 11, bold=True, align="center")
        text(f"重置：{item[2]}", cx, y + 162, 9, C["secondary"], align="center")


def balance_card(x, y, w, h, balance, percent):
    card(x, y, w, h, "DeepSeek", "CNY")
    gauge(x + w / 2, y + 88, 42, percent, balance)
    text("余额 · ¥100 满偏", x + w / 2, y + 157, 11, C["secondary"], align="center")


def home_icon(x, y, color):
    pts = coords((x - 8, y, x, y - 7, x + 8, y))
    draw.line(pts, fill=color, width=2 * SCALE, joint="curve")
    draw.line(coords((x - 5, y, x - 5, y + 8, x + 5, y + 8, x + 5, y)), fill=color, width=2 * SCALE)


def settings_icon(x, y, color):
    draw.ellipse(coords((x - 7, y - 7, x + 7, y + 7)), outline=color, width=2 * SCALE)
    draw.ellipse(coords((x - 2, y - 2, x + 2, y + 2)), fill=color)


KIMI = ((24, "5 小时", "今天 20:00"), (29, "本周", "11 月 9 日"))
CHATGPT = ((0, "5 小时", "今天 21:00"), (17, "本周", "11 月 10 日"))
GEMINI = ((0, "5 小时", "今天 22:30"), (24, "周额度", "11 月 11 日"))

# Heading and device labels.
text("QuotaGauge · 弧形仪表设计稿", 40, 34, 24, bold=True)
text("静态预览 · 1600×900", 1560, 35, 12, C["secondary"], align="right")
text("手机 · 360dp 内容宽 · 单列", 40, 76, 12, C["secondary"], bold=True)
text("平板 · 双列 + NavigationRail", 500, 76, 12, C["secondary"], bold=True)

# Screens.
rounded_rect(40, 100, 410, 750, 26, C["screen"], C["border"])
rounded_rect(500, 100, 1060, 750, 26, C["screen"], C["border"])

# Phone top bar and single-column cards.
text("额度盘", 65, 136, 19, bold=True)
text("刷新", 365, 136, 11, align="right")
text("设置", 425, 136, 11, align="right")
line(57, 158, 433, 158, "#eeeeee")
for y, name, tag, values in (
    (175, "Kimi", "Kimi", KIMI),
    (394, "ChatGPT", "OpenAI", CHATGPT),
    (613, "Gemini", "Gemini", GEMINI),
):
    dual_card(65, y, 360, 205, name, tag, *values)

# Tablet rail.
rounded_rect(514, 116, 62, 52, 26, C["track"])
home_icon(545, 132, C["primary"])
text("额度", 545, 155, 10, align="center")
settings_icon(545, 204, C["secondary"])
text("设置", 545, 226, 10, C["secondary"], align="center")
line(590, 100, 590, 850, "#e5e5e5")
text("额度盘", 614, 136, 19, bold=True)
text("刷新", 1536, 136, 11, align="right")
line(606, 158, 1544, 158, "#eeeeee")

# Tablet: the same three cards, plus two DeepSeek balance examples.
dual_card(614, 175, 454, 205, "Kimi", "Kimi", *KIMI)
dual_card(1082, 175, 454, 205, "ChatGPT", "OpenAI", *CHATGPT)
dual_card(614, 394, 454, 205, "Gemini", "Gemini", *GEMINI)
balance_card(1082, 394, 454, 205, "¥114.87", 100)
balance_card(614, 613, 454, 205, "¥82.90", 82.9)

# Continuous color scales; no threshold-based color switching.
text("额度：0% 绿 → 50% 黄 → 100% 红", 540, 879, 11, C["secondary"])
text("余额：0% 红 → 50% 黄 → 100% 绿", 1060, 879, 11, C["secondary"], align="right")

image = image.resize((W, H), Image.Resampling.LANCZOS)
image.save(OUT, "PNG", optimize=True)
print(OUT)
