import colorsys, pathlib

def hue_color(h_deg, s=0.72, v=0.92):
    r, g, b = colorsys.hsv_to_rgb((h_deg % 360) / 360.0, s, v)
    return '#%02x%02x%02x' % (int(r * 255), int(g * 255), int(b * 255))

def quota_arc(pct):
    """用量比例 -> 绿(120) 到 红(0)"""
    p = max(0.0, min(1.0, pct / 100.0))
    h = 120 * (1 - p)
    c1 = hue_color(h + 12)
    c2 = hue_color(h)
    deg = p * 360
    if p <= 0:
        return 'conic-gradient(#eef1f5 0 360deg)'
    return f'conic-gradient(from -90deg,{c1} 0deg,{c2} {deg:.1f}deg,#eef1f5 {deg:.1f}deg 360deg)'

def balance_arc(pct):
    """余额比例 -> 少红多绿"""
    p = max(0.0, min(1.0, pct / 100.0))
    h = 120 * p
    c1 = hue_color(h + 12)
    c2 = hue_color(h)
    deg = p * 360
    if p <= 0:
        return 'conic-gradient(#eef1f5 0 360deg)'
    return f'conic-gradient(from -90deg,{c1} 0deg,{c2} {deg:.1f}deg,#eef1f5 {deg:.1f}deg 360deg)'

def card(name, prov, gauges, money=None):
    if money:
        pct, txt = money
        return f'''<div class="card"><div class="head"><span class="name">{name}</span><span class="prov">{prov}</span></div>
        <div class="bal"><div class="arc" style="background:{balance_arc(pct)}"><div class="pct">{pct:.0f}%</div></div>
        <div><div class="money">¥{txt}</div><div class="lbl">按 100 元满偏</div></div></div></div>'''
    cells = ''.join(
        f'<div class="g"><div class="arc" style="background:{quota_arc(v)}"><div class="pct">{v:.0f}%</div></div>'
        f'<div class="lbl">{lab}</div><div class="reset">{reset}</div></div>'
        for lab, v, reset in gauges)
    return f'''<div class="card"><div class="head"><span class="name">{name}</span><span class="prov">{prov}</span></div>
    <div class="gauges">{cells}</div></div>'''

kimi = card('老爹', 'Kimi', [('5 小时', 24, '重置 09:36'), ('本周', 29, '重置 16:36')])
chat = card('ChatGPT Plus', 'ChatGPT Plus', [('5 小时', 0, '重置 13:01'), ('本周', 17, '重置 11-12')])
gem = card('Gemini', 'Gemini（网页版）', [('5 小时', 0, '重置 8:37'), ('周额度', 24, '重置 11-10')])
ds1 = card('我的', 'DeepSeek · 余额', [], money=(100, '114.87'))
ds2 = card('老爹', 'DeepSeek · 余额', [], money=(83, '82.90'))

html = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
:root{{--bg:#f7f8fa;--card:#fff;--line:#e6e8ec;--txt:#1d2129;--sub:#86909c}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);font-family:"Microsoft YaHei","PingFang SC",Arial,sans-serif;color:var(--txt)}}
.wrap{{width:1600px;padding:28px 32px;display:flex;gap:36px}}
h3{{margin:0 0 14px;font-size:15px;color:var(--sub);letter-spacing:1px}}
.phone{{width:360px;display:flex;flex-direction:column;gap:12px}}
.tablet{{flex:1;display:grid;grid-template-columns:1fr 1fr;gap:16px;align-content:start}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px}}
.head{{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:8px}}
.name{{font-size:16px;font-weight:700}}.prov{{font-size:12px;color:var(--sub)}}
.gauges{{display:flex;gap:24px;margin-top:6px}}
.g{{display:flex;flex-direction:column;align-items:center;gap:6px}}
.arc{{position:relative;width:98px;height:98px;border-radius:50%}}
.arc::after{{content:"";position:absolute;inset:10px;border-radius:50%;background:var(--card)}}
.pct{{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:700;z-index:2}}
.lbl,.reset{{font-size:11px;color:var(--sub)}}
.bal{{display:flex;align-items:center;gap:16px;margin-top:6px}}.money{{font-size:26px;font-weight:800}}
</style></head><body><div class="wrap">
<div><h3>手机 · 单列</h3><div class="phone">{kimi}{chat}{gem}</div></div>
<div style="flex:1"><h3>平板 · 双列 · 限宽居中</h3><div class="tablet">{kimi}{ds1}{ds2}{chat}{gem}</div></div>
</div></body></html>'''

p = pathlib.Path(r'C:\Users\Administrator\.openclaw\workspace\projects\cpa-native\preview')
(p / 'gauge-preview-v3.html').write_text(html, encoding='utf-8')
print('written')
