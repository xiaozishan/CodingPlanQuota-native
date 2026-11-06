import colorsys, pathlib, math

def hue(h):
    r, g, b = colorsys.hsv_to_rgb((max(0, min(360, h)) % 360) / 360, 0.72, 0.9)
    return '#%02x%02x%02x' % (int(r*255), int(g*255), int(b*255))

R, STROKE, SPAN = 46, 11, 270.0
C = 2 * math.pi * R
TRACK = C * SPAN / 360.0

def gauge(pct, reverse=False, size=110):
    p = max(0.0, min(1.0, pct/100.0))
    h = 120*p if reverse else 120*(1-p)
    col = hue(h)
    fill = TRACK * p
    gid = f"g{abs(hash((pct, reverse)))%99999}"
    return f'''<svg width="{size}" height="{size}" viewBox="0 0 110 110">
  <defs><linearGradient id="{gid}" x1="0" y1="1" x2="1" y2="0">
    <stop offset="0%" stop-color="{hue(h+22)}"/><stop offset="100%" stop-color="{col}"/>
  </linearGradient></defs>
  <g transform="rotate(135 55 55)">
    <circle cx="55" cy="55" r="{R}" fill="none" stroke="#eceff3" stroke-width="{STROKE}"
            stroke-dasharray="{TRACK:.1f} {C:.1f}" stroke-linecap="round"/>
    <circle cx="55" cy="55" r="{R}" fill="none" stroke="url(#{gid})" stroke-width="{STROKE}"
            stroke-dasharray="{fill:.1f} {C:.1f}" stroke-linecap="round"/>
  </g>
  <text x="55" y="58" text-anchor="middle" font-size="18" font-weight="700" fill="#1d2129">{pct:.0f}%</text>
</svg>'''

def quota_card(name, prov, items):
    cells = ''.join(
        f'<div class="g">{gauge(v)}<div class="lbl">{lab}</div><div class="reset">{reset}</div></div>'
        for lab, v, reset in items)
    return f'<div class="card"><div class="head"><span class="name">{name}</span><span class="prov">{prov}</span></div><div class="gauges">{cells}</div></div>'

def balance_card(name, prov, pct, money):
    return f'''<div class="card"><div class="head"><span class="name">{name}</span><span class="prov">{prov}</span></div>
    <div class="bal">{gauge(pct, reverse=True, size=120)}<div><div class="money">¥{money}</div><div class="lbl">按 100 元满偏，越少越红</div></div></div></div>'''

kimi = quota_card('老爹', 'Kimi', [('5 小时', 24, '重置 09:36'), ('本周', 29, '重置 16:36')])
chat = quota_card('ChatGPT Plus', 'ChatGPT Plus', [('5 小时', 0, '重置 13:01'), ('本周', 17, '重置 11-12')])
gem = quota_card('Gemini', 'Gemini（网页版）', [('5 小时', 0, '重置 8:37'), ('周额度', 24, '重置 11-10')])
ds1 = balance_card('我的', 'DeepSeek · 余额', 100, '114.87')
ds2 = balance_card('老爹', 'DeepSeek · 余额', 83, '82.90')

html = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
:root{{--bg:#f7f8fa;--card:#fff;--line:#e6e8ec;--txt:#1d2129;--sub:#86909c}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);font-family:"Microsoft YaHei","PingFang SC",Arial,sans-serif;color:var(--txt)}}
.wrap{{width:1600px;padding:28px 32px;display:flex;gap:36px}}
h3{{margin:0 0 14px;font-size:15px;color:var(--sub);letter-spacing:1px}}
.phone{{width:360px;display:flex;flex-direction:column;gap:12px}}
.tablet{{flex:1;display:grid;grid-template-columns:1fr 1fr;gap:16px;align-content:start}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px}}
.head{{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px}}
.name{{font-size:16px;font-weight:700}}.prov{{font-size:12px;color:var(--sub)}}
.gauges{{display:flex;gap:22px;margin-top:4px}}
.g{{display:flex;flex-direction:column;align-items:center;gap:4px}}
.lbl,.reset{{font-size:11px;color:var(--sub)}}
.bal{{display:flex;align-items:center;gap:16px;margin-top:4px}}.money{{font-size:26px;font-weight:800}}
</style></head><body><div class="wrap">
<div><h3>手机 · 单列</h3><div class="phone">{kimi}{chat}{gem}</div></div>
<div style="flex:1"><h3>平板 · 双列 · 限宽居中</h3><div class="tablet">{kimi}{ds1}{ds2}{chat}{gem}</div></div>
</div></body></html>'''

p = pathlib.Path(r'C:\Users\Administrator\.openclaw\workspace\projects\cpa-native\preview')
(p / 'gauge-preview-v4.html').write_text(html, encoding='utf-8')
print('written v4')
