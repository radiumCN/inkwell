"""生成桌面图标：4 种图形 × 5 种纸色 = 20 个自适应图标（背景 / 前景 / 单色三层，全是矢量）。

用法（在仓库根目录）:
    python tools/gen_launcher_icons.py                 # 写 app/src/main/res 下的图标资源
    python tools/gen_launcher_icons.py --preview x.html # 另出一张 SVG 预览（和写进资源的是同一份几何）

图形和纸色的意思、桌面入口（activity-alias）怎么对应，见 app/.../util/AppIcon.kt。
改图形就改这里再跑一遍，生成的 XML 别手改。

网格照 Android 自适应图标：每层 108 × 108，桌面只露中间 72（18..90），图形收在直径 66 的安全区附近。
单色层（Android 13 的主题图标）由系统按主题色着色，只看形状和透明度，所以画成单色、再缩一圈。
"""
import argparse
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# ---------- 纸色：取阅读器的纸张（reader 的 ReaderSettings 预设），各配一个强调色 ----------
# key, 纸上, 纸下, 字色, 强调, 强调深, 深色？, 翻角（亮, 暗, 尖）
PAPERS = [
    ("paper", "#F8F2E1", "#EDE3C8", "#3A3226", "#B0561F", "#7E3A12", False, ("#FFFFFF", "#E3D7B6", "#F8F2E1")),
    ("white", "#FFFFFF", "#EEF1F5", "#24272C", "#3482FF", "#1F5FD6", False, ("#FFFFFF", "#DDE2EA", "#FFFFFF")),
    ("bamboo", "#DDF0DE", "#C2E1C6", "#2E4033", "#3E7A55", "#2B5A3D", False, ("#F4FBF4", "#B3D6B8", "#DDF0DE")),
    ("inkblue", "#1B2333", "#0D121B", "#AFC3DE", "#6F95CC", "#47689A", True, ("#3C4A63", "#222C3E", "#1B2333")),
    ("night", "#262626", "#121212", "#C9C9C9", "#E8A33D", "#B67A1E", True, ("#4A4A4A", "#2C2C2C", "#262626")),
]
SHAPES = ["mark", "turn", "glass", "para"]
MONO = "#FFFFFF"


# ---------- 图元：一份几何，两种写法（VectorDrawable / SVG） ----------
class Linear:
    def __init__(self, x1, y1, x2, y2, stops):
        self.x1, self.y1, self.x2, self.y2, self.stops = x1, y1, x2, y2, stops


class Path:
    def __init__(self, d, fill=None, alpha=1.0, stroke=None, width=0.0, stroke_alpha=1.0, evenodd=False):
        self.d, self.fill, self.alpha = d, fill, alpha
        self.stroke, self.width, self.stroke_alpha, self.evenodd = stroke, width, stroke_alpha, evenodd


class Group:
    def __init__(self, children, rotate=0.0, scale=1.0, pivot=(54, 54), ty=0.0):
        self.children, self.rotate, self.scale, self.pivot, self.ty = children, rotate, scale, pivot, ty


def rrect(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} V{y + h - r} "
            f"A{r},{r} 0 0 1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} "
            f"V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy} Z"


def line(x1, x2, y):
    return f"M{x1},{y} H{x2}"


def capsule(x1, x2, y, h):
    """横的胶囊（线的实心版）：单色层里要在一块面上挖出字线，得是闭合的形状"""
    return rrect(x1 - h / 2, y - h / 2, x2 - x1 + h, h, h / 2)


def full(fill):
    return Path("M0,0 H108 V108 H0 Z", fill=fill)


def vgrad(top, bottom):
    return Linear(0, 0, 0, 108, [(0, top), (1, bottom)])


# ---------- 四种图形 ----------
RIBBON = "M45,45 H63 V85 L54,77.5 L45,85 Z"
MARK_SCALE = 0.86
PARA_SCALE = 0.9


def fg_mark(p):
    """书签（i。）：小写 i —— 竖是一条书签丝带（尾巴剪成燕尾），点是一个句号「。」"""
    _, top, bottom, ink, accent, accent_dark, dark, _ = p
    # 整体收一圈：原尺寸的句号圈顶到可见区上沿只剩 3，圆形蒙版的启动器上会被切
    return [Group([
        Path(circle(54, 31, 7.2), stroke=ink, width=5),
        Path(RIBBON, fill=accent),
        # 丝带上端深一截：像是从书页里抽出来的
        Path("M45,45 H63 V50 H45 Z", fill=accent_dark, alpha=0.55),
    ], scale=MARK_SCALE)]


TURN_PAGE = "M0,0 H108 V32 L32,108 H0 Z"
TURN_FLAP = "M36,104 C47,86 54,72 59,59 C72,54 86,47 104,36 Z"
TURN_SHADOW = "M33,104 C44,85 51,70 56.5,56.5 C70,51 85,44 104,33 L104,36 L36,104 Z"
TURN_LINES = [(38, 74, 31), (28, 74, 40.5), (28, 62, 50), (28, 46, 59.5)]


def fg_turn(p):
    """翻页：整块是一页纸，右下角正翻过去（底下是背景层 = 下一页的颜色）"""
    _, top, bottom, ink, accent, accent_dark, dark, (hi, lo, tip) = p
    flap = Linear(93.8, 93.8, 53, 53, [(0, lo), (0.45, hi), (1, tip)])
    return [
        Path(TURN_PAGE, fill=vgrad(top, bottom)),
        Path(TURN_SHADOW, fill="#000000", alpha=0.45 if dark else 0.13),
        Path(TURN_FLAP, fill=flap),
    ] + [Path(line(x1, x2, y), stroke=ink, width=4.4, stroke_alpha=0.8) for x1, x2, y in TURN_LINES]


GLASS_BACK = rrect(29, 27, 36, 46, 10)
GLASS_FRONT = rrect(42, 36, 36, 46, 10)
GLASS_LINES = [(49, 71, 51), (49, 71, 59), (49, 63, 67)]


def fg_glass(p):
    """叠页：后一页强调色，前一页是磨砂玻璃（半透 + 边缘高光），隔着它透出后页"""
    _, top, bottom, ink, accent, accent_dark, dark, _ = p
    back = Group([Path(GLASS_BACK, fill=Linear(0, 27, 0, 73, [(0, accent), (1, accent_dark)]))],
                 rotate=-9, pivot=(47, 50))
    front = Group(
        [Path(GLASS_FRONT, fill="#FFFFFF", alpha=0.16 if dark else 0.55,
              stroke="#FFFFFF", width=1.3, stroke_alpha=0.5 if dark else 0.95)]
        + [Path(line(x1, x2, y), stroke=ink, width=3.6, stroke_alpha=0.7 if dark else 0.55)
           for x1, x2, y in GLASS_LINES],
        rotate=6, pivot=(60, 58))
    return [back, front]


PARA_LINES = [(50, 80, 33), (28, 80, 44.5), (28, 80, 56), (28, 80, 67.5), (28, 52, 79)]


def fg_para(p):
    """段落：一段中文 —— 首行缩进两格，末行短一截，句末一个句号"""
    _, top, bottom, ink, accent, accent_dark, dark, _ = p
    # 先上移 3.5 把重心放回正中，再收一圈：满幅的字线左右离可见区边只剩 7，像要溢出去
    return [Group([Group(
        [Path(line(x1, x2, y), stroke=ink, width=5.6) for x1, x2, y in PARA_LINES]
        + [Path(circle(63, 79, 4.2), stroke=accent, width=3.6)],
        ty=-3.5)], scale=PARA_SCALE)]


FG = {"mark": fg_mark, "turn": fg_turn, "glass": fg_glass, "para": fg_para}

# 单色层：同一个图形的单色版，缩一圈（主题图标在桌面上整体比彩色的小，满幅的话会顶到圆形蒙版的边）
MONO_SCALE = 0.8


def mono(shape):
    if shape == "mark":
        body = [Path(circle(54, 31, 7.2), stroke=MONO, width=5), Path(RIBBON, fill=MONO)]
    elif shape == "turn":
        # 满幅的那页纸放不进单色层：画成一页带折角的纸，三行字
        page = "M38,26 H70 A6,6 0 0 1 76,32 V62 L56,82 H38 A6,6 0 0 1 32,76 V32 A6,6 0 0 1 38,26 Z"
        body = [
            Path(page, stroke=MONO, width=4),
            Path("M56,82 L76,62 H62 A6,6 0 0 0 56,68 Z", fill=MONO),
        ] + [Path(line(x1, x2, y), stroke=MONO, width=4) for x1, x2, y in [(41, 67, 38), (41, 67, 47), (41, 57, 56)]]
    elif shape == "glass":
        holes = " ".join(capsule(x1, x2, y, 3.6) for x1, x2, y in GLASS_LINES)
        body = [
            Group([Path(GLASS_BACK, fill=MONO, alpha=0.45)], rotate=-9, pivot=(47, 50)),
            Group([Path(f"{GLASS_FRONT} {holes}", fill=MONO, evenodd=True)], rotate=6, pivot=(60, 58)),
        ]
    else:
        body = [Group([Path(line(x1, x2, y), stroke=MONO, width=5.6) for x1, x2, y in PARA_LINES]
                      + [Path(circle(63, 79, 4.2), stroke=MONO, width=3.6)], ty=-3.5)]
    return [Group(body, scale=MONO_SCALE)]


# ---------- VectorDrawable ----------
def argb(color):
    return "#FF" + color[1:].upper()


def vd_color(attr, value, alpha, indent):
    pad = " " * indent
    if isinstance(value, Linear):
        items = "\n".join(f'{pad}            <item android:offset="{o}" android:color="{argb(c)}" />' for o, c in value.stops)
        return (f'{pad}    <aapt:attr name="android:{attr}">\n'
                f'{pad}        <gradient android:type="linear" android:startX="{value.x1}" android:startY="{value.y1}" '
                f'android:endX="{value.x2}" android:endY="{value.y2}">\n{items}\n'
                f'{pad}        </gradient>\n{pad}    </aapt:attr>\n')
    return None


def vd_node(node, indent):
    pad = " " * indent
    if isinstance(node, Group):
        attrs = []
        if node.rotate:
            attrs += [f'android:rotation="{node.rotate}"']
        if node.scale != 1.0:
            attrs += [f'android:scaleX="{node.scale}"', f'android:scaleY="{node.scale}"']
        if node.rotate or node.scale != 1.0:
            attrs += [f'android:pivotX="{node.pivot[0]}"', f'android:pivotY="{node.pivot[1]}"']
        if node.ty:
            attrs += [f'android:translateY="{node.ty}"']
        inner = "".join(vd_node(c, indent + 4) for c in node.children)
        return f'{pad}<group {" ".join(attrs)}>\n{inner}{pad}</group>\n'
    attrs = [f'android:pathData="{node.d}"']
    children = ""
    if node.fill is not None:
        if isinstance(node.fill, Linear):
            children += vd_color("fillColor", node.fill, 1, indent)
        else:
            attrs.append(f'android:fillColor="{argb(node.fill)}"')
        if node.alpha != 1.0:
            attrs.append(f'android:fillAlpha="{node.alpha}"')
        if node.evenodd:
            attrs.append('android:fillType="evenOdd"')
    if node.stroke is not None:
        attrs += [f'android:strokeColor="{argb(node.stroke)}"', f'android:strokeWidth="{node.width}"',
                  'android:strokeLineCap="round"']
        if node.stroke_alpha != 1.0:
            attrs.append(f'android:strokeAlpha="{node.stroke_alpha}"')
    joined = ("\n" + pad + "    ").join(attrs)
    if children:
        return f'{pad}<path\n{pad}    {joined}>\n{children}{pad}</path>\n'
    return f'{pad}<path\n{pad}    {joined} />\n'


HEADER = "<!-- 由 tools/gen_launcher_icons.py 生成，改图形改脚本再跑一遍，别手改 -->\n"


def vector(nodes):
    body = "".join(vd_node(n, 4) for n in nodes)
    return ('<?xml version="1.0" encoding="utf-8"?>\n' + HEADER +
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + body + '</vector>\n')


def adaptive(bg, fg, mono_name):
    return ('<?xml version="1.0" encoding="utf-8"?>\n' + HEADER +
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/{bg}" />\n'
            f'    <foreground android:drawable="@drawable/{fg}" />\n'
            f'    <monochrome android:drawable="@drawable/{mono_name}" />\n'
            '</adaptive-icon>\n')


def layers(shape, p):
    """(背景资源名, 背景图元, 前景资源名)。翻页的背景是下一页（强调色），其余是纸"""
    key = p[0]
    if shape == "turn":
        return f"ic_launcher_bg_turn_{key}", [full(Linear(0, 0, 108, 108, [(0, p[4]), (1, p[5])]))], f"ic_launcher_fg_turn_{key}"
    return f"ic_launcher_bg_{key}", [full(vgrad(p[1], p[2]))], f"ic_launcher_fg_{shape}_{key}"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # 仓库里资源文件是 LF 入库；Windows 工作区由 git 按 autocrlf 转，这里统一写 LF
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def generate():
    drawable = os.path.join(RES, "drawable")
    mipmap = os.path.join(RES, "mipmap-anydpi-v26")
    written = set()
    for shape in SHAPES:
        write(os.path.join(drawable, f"ic_launcher_mono_{shape}.xml"), vector(mono(shape)))
        for p in PAPERS:
            bg_name, bg_nodes, fg_name = layers(shape, p)
            if bg_name not in written:
                write(os.path.join(drawable, bg_name + ".xml"), vector(bg_nodes))
                written.add(bg_name)
            write(os.path.join(drawable, fg_name + ".xml"), vector(FG[shape](p)))
            write(os.path.join(mipmap, f"ic_launcher_{shape}_{p[0]}.xml"),
                  adaptive(bg_name, fg_name, f"ic_launcher_mono_{shape}"))
    print(f"wrote {len(SHAPES) * len(PAPERS)} icons to {RES}")


# ---------- SVG 预览 ----------
def svg_paint(value, defs, gid):
    if isinstance(value, Linear):
        stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in value.stops)
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{value.x1}" y1="{value.y1}" '
                    f'x2="{value.x2}" y2="{value.y2}">{stops}</linearGradient>')
        return f"url(#{gid})"
    return value


def svg_node(node, defs, prefix):
    if isinstance(node, Group):
        px, py = node.pivot
        t = []
        if node.ty:
            t.append(f"translate(0,{node.ty})")
        if node.rotate:
            t.append(f"rotate({node.rotate} {px} {py})")
        if node.scale != 1.0:
            t.append(f"translate({px},{py}) scale({node.scale}) translate({-px},{-py})")
        inner = "".join(svg_node(c, defs, f"{prefix}{i}") for i, c in enumerate(node.children))
        return f'<g transform="{" ".join(t)}">{inner}</g>'
    a = [f'd="{node.d}"']
    if node.fill is not None:
        a.append(f'fill="{svg_paint(node.fill, defs, prefix + "f")}" fill-opacity="{node.alpha}"')
        if node.evenodd:
            a.append('fill-rule="evenodd"')
    else:
        a.append('fill="none"')
    if node.stroke is not None:
        a.append(f'stroke="{node.stroke}" stroke-width="{node.width}" stroke-linecap="round" '
                 f'stroke-opacity="{node.stroke_alpha}"')
    return f'<path {" ".join(a)}/>'


def svg_icon(nodes, size, uid, crop=True):
    defs = []
    body = "".join(svg_node(n, defs, f"{uid}_{i}_") for i, n in enumerate(nodes))
    box = "18 18 72 72" if crop else "0 0 108 108"
    return (f'<svg viewBox="{box}" width="{size}" height="{size}"><defs>{"".join(defs)}</defs>{body}</svg>')


def preview(out):
    names = {"mark": "书签", "turn": "翻页", "glass": "叠页", "para": "段落"}
    papers = {"paper": "暖纸", "white": "净白", "bamboo": "青竹", "inkblue": "墨蓝", "night": "夜色"}
    rows = []
    for shape in SHAPES:
        cells = []
        for p in PAPERS:
            _, bg_nodes, _ = layers(shape, p)
            nodes = bg_nodes + FG[shape](p)
            cells.append(f'<div class="c"><div class="t">{svg_icon(nodes, 104, f"{shape}{p[0]}")}</div>'
                         f'<span>{papers[p[0]]}</span></div>')
        small = svg_icon(layers(shape, PAPERS[0])[1] + FG[shape](PAPERS[0]), 40, f"{shape}s")
        themed = svg_icon([full("#DCE5F4")] + [Group([n], ) for n in mono(shape)], 104, f"{shape}m")
        themed = themed.replace(MONO, "#2F4A78")
        cells.append(f'<div class="c"><div class="t">{small}</div><span>40px</span></div>')
        cells.append(f'<div class="c"><div class="t">{themed}</div><span>单色层</span></div>')
        rows.append(f'<div class="r"><b>{names[shape]}</b>{"".join(cells)}</div>')
    html = ('<!DOCTYPE html><html><head><meta charset="utf-8"><style>'
            'body{font-family:"Microsoft YaHei",sans-serif;background:#ececec;padding:24px;width:1080px}'
            '.r{display:flex;gap:18px;align-items:flex-end;background:#fff;border-radius:16px;padding:16px 20px;margin-bottom:12px}'
            '.r b{width:48px;align-self:center}.c{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:12px;color:#666}'
            '.t{border-radius:23%;overflow:hidden;line-height:0;box-shadow:0 1px 2px rgba(0,0,0,.12)}'
            '</style></head><body>' + "".join(rows) + '</body></html>')
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print("wrote", out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", help="另出一张 SVG 预览 html（不写资源）")
    args = ap.parse_args()
    if args.preview:
        preview(args.preview)
    else:
        generate()
