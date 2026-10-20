"""エミュレーターのスクリーンショットから、発表用の画像を作る。

出力:
  docs/screenshots/watch/NN_name.png   丸く切り抜いた時計画像（背景透過）
  docs/screenshots/slides/*.png        16:9 のスライド画像（説明付き）
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path("/home/o9oem/workspace/mine/biz/app_notif_to_wearos/docs/screenshots")
RAW = ROOT / "raw"
WATCH = ROOT / "watch"
SLIDES = ROOT / "slides"
FONT_R = ("/mnt/c/Windows/Fonts/BIZ-UDGothicR.ttc", 1)
FONT_B = ("/mnt/c/Windows/Fonts/BIZ-UDGothicB.ttc", 1)

INK = (33, 37, 41)
SUB = (90, 98, 108)
ACCENT = (198, 40, 40)
LINE = (222, 226, 230)
BG = (255, 255, 255)
PANEL = (246, 247, 249)
WATCH_TOP = 250


def font(spec, size):
    path, index = spec
    return ImageFont.truetype(path, size, index=index)


def watch_image(raw_name: str, size: int = 520) -> Image.Image:
    """スクリーンショットを丸く切り抜き、時計のベゼルと影を付ける。"""
    screen = Image.open(RAW / f"{raw_name}.png").convert("RGBA")
    bezel = int(size * 0.045)
    inner = size - bezel * 2
    screen = screen.resize((inner, inner), Image.LANCZOS)

    mask = Image.new("L", (inner * 4, inner * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, inner * 4 - 1, inner * 4 - 1), fill=255)
    mask = mask.resize((inner, inner), Image.LANCZOS)

    pad = int(size * 0.08)
    canvas = Image.new("RGBA", (size + pad * 2, size + pad * 2), (0, 0, 0, 0))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse(
        (pad, pad + int(size * 0.02), pad + size, pad + size + int(size * 0.02)), fill=(0, 0, 0, 90)
    )
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(size * 0.03)))

    ring = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    draw = ImageDraw.Draw(ring)
    draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=(38, 40, 44, 255))
    draw.ellipse((6, 6, size * 4 - 7, size * 4 - 7), outline=(92, 96, 104, 255), width=8)
    ring = ring.resize((size, size), Image.LANCZOS)
    canvas.alpha_composite(ring, (pad, pad))
    canvas.paste(screen, (pad + bezel, pad + bezel), mask)
    return canvas


def wrap(text: str, fnt: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines, current = [], ""
    for ch in text:
        if ch == "\n":
            lines.append(current)
            current = ""
            continue
        if fnt.getlength(current + ch) > width and current:
            lines.append(current)
            current = ch
        else:
            current += ch
    if current:
        lines.append(current)
    return lines


def header(draw: ImageDraw.ImageDraw, title: str, subtitle: str, width: int) -> None:
    draw.rectangle((0, 0, 14, 150), fill=ACCENT)
    draw.text((70, 54), title, font=font(FONT_B, 56), fill=INK)
    draw.text((72, 128), subtitle, font=font(FONT_R, 26), fill=SUB)
    draw.line((70, 182, width - 70, 182), fill=LINE, width=2)


def footer(draw: ImageDraw.ImageDraw, text: str, width: int, height: int) -> None:
    draw.text((70, height - 56), text, font=font(FONT_R, 20), fill=SUB)


def flow_slide(filename: str, title: str, subtitle: str, steps: list[tuple[str, str, str, str]], note: str):
    width, height = 1920, 1080
    slide = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(slide)
    header(draw, title, subtitle, width)

    count = len(steps)
    margin = 60
    col = (width - margin * 2) // count
    watch_size = min(330, col - 30)
    for i, (raw_name, number, heading, body) in enumerate(steps):
        x0 = margin + i * col
        cx = x0 + col // 2
        watch = watch_image(raw_name, watch_size)
        slide.paste(watch, (cx - watch.width // 2, WATCH_TOP), watch)

        top = WATCH_TOP + watch.height + 10
        badge = 46
        draw.ellipse((cx - badge // 2, top, cx + badge // 2, top + badge), fill=ACCENT)
        num_font = font(FONT_B, 24)
        draw.text((cx, top + badge // 2), number, font=num_font, fill=BG, anchor="mm")

        head_font = font(FONT_B, 32)
        draw.text((cx, top + badge + 34), heading, font=head_font, fill=INK, anchor="mm")
        body_font = font(FONT_R, 24)
        y = top + badge + 70
        for line in wrap(body, body_font, col - 16):
            draw.text((cx, y), line, font=body_font, fill=SUB, anchor="mt")
            y += 38
        if i < count - 1:
            ay = WATCH_TOP + watch.height // 2
            ax = x0 + col - 8
            draw.polygon([(ax - 14, ay - 14), (ax + 6, ay), (ax - 14, ay + 14)], fill=(190, 196, 204))

    footer(draw, note, width, height)
    SLIDES.mkdir(parents=True, exist_ok=True)
    slide.save(SLIDES / filename, optimize=True)


def results_slide(filename: str, rows: list[tuple[str, str, str]], limits: list[str], note: str):
    width, height = 1920, 1080
    slide = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(slide)
    header(draw, "検証結果（Wear OS 5 エミュレーター）", "Firebase 経由の実送信で、登録から警告の停止までを確認", width)

    label_font, value_font, mark_font = font(FONT_B, 27), font(FONT_R, 25), font(FONT_B, 26)
    x_label, x_value, x_mark = 90, 520, 1660
    y = 220
    draw.rectangle((70, y, width - 70, y + 54), fill=PANEL)
    for x, text in ((x_label, "確認項目"), (x_value, "結果"), (x_mark, "判定")):
        draw.text((x, y + 27), text, font=font(FONT_B, 24), fill=SUB, anchor="lm")
    y += 54
    for label, value, mark in rows:
        lines = wrap(value, value_font, x_mark - x_value - 40)
        row_h = max(70, 26 + 36 * len(lines))
        draw.text((x_label, y + row_h // 2), label, font=label_font, fill=INK, anchor="lm")
        ty = y + (row_h - 36 * len(lines)) // 2 + 4
        for line in lines:
            draw.text((x_value, ty), line, font=value_font, fill=INK)
            ty += 36
        color = (46, 125, 50) if mark == "OK" else (230, 126, 34)
        draw.text((x_mark, y + row_h // 2), mark, font=mark_font, fill=color, anchor="lm")
        y += row_h
        draw.line((70, y, width - 70, y), fill=LINE, width=2)

    y += 30
    draw.text((90, y), "制約・今後の確認事項", font=font(FONT_B, 28), fill=ACCENT)
    y += 50
    for item in limits:
        for j, line in enumerate(wrap(item, value_font, width - 260)):
            prefix = "・" if j == 0 else "　"
            draw.text((100, y), prefix + line, font=value_font, fill=INK)
            y += 36
    footer(draw, note, width, height)
    slide.save(SLIDES / filename, optimize=True)


def main() -> None:
    WATCH.mkdir(parents=True, exist_ok=True)
    for raw in sorted(RAW.glob("*.png")):
        watch_image(raw.stem, 520).save(WATCH / raw.name, optimize=True)

    note = "画面: Wear OS 5（API 34）エミュレーター / 2026-10-19 撮影 / Firebase（business-484010）経由の実送信"
    flow_slide(
        "01_registration_flow.png",
        "端末登録の流れ（キッティング時に 1 回）",
        "入力は「現場」と「端末」の 2 項目だけ。登録後は番号を大きく表示して誤操作を防ぐ",
        [
            ("01_permission", "1", "通知の許可", "初回起動時に通知の許可を確認\n※エミュレーターでは英語表示"),
            ("02_register_empty", "2", "端末登録画面", "入力は「現場」と「端末」の\n2 項目だけ"),
            ("03_keypad", "3", "数字テンキー", "入力欄をタップすると\n数字だけのキーボードが出る"),
            ("04_register_filled", "4", "番号を入力", "現場 101・端末 5 を入れて\n「登録 / 更新」を押す"),
            ("05_registered_standby", "5", "登録完了", "番号を大きく表示して待機\n変更は長押しのみ"),
        ],
        note,
    )
    flow_slide(
        "02_alert_flow.png",
        "警告の受信から確認まで",
        "閾値超過を検知すると、振動とアラーム音を確認されるまで繰り返す（最大 60 秒）",
        [
            ("06_alert_received", "1", "警告を受信", "振動とアラーム音で知らせ\n手首を上げると内容を表示"),
            ("07_alert_actions", "2", "通知の操作", "「確認」で停止\n「開く」で警告画面を表示"),
            ("08_alert_screen", "3", "警告画面", "警告内容と送信時刻\n現場・端末の番号を表示"),
            ("09_after_ack", "4", "確認で停止", "振動と音が止まり\n通常の画面に戻る"),
            ("10_test_notification", "5", "テスト通知", "キッティング時に\n鳴動を確かめる通知"),
        ],
        note,
    )
    results_slide(
        "03_verification_results.png",
        [
            ("端末登録", "アプリが FCM の宛先 ID を登録 API へ送信し、DB に保存。画面は「待機中」", "OK"),
            ("警告の受信", "画面消灯・省電力（Doze）中も受信。計測した回では送信から約 1 秒で鳴動開始", "OK"),
            ("振動・アラーム音", "1 秒振動＋0.5 秒休止の繰り返しと、アラーム用途の音を再生", "OK"),
            ("自動停止", "確認されない場合は 60 秒で停止し、通知は残る", "OK"),
            ("確認操作", "「確認」から約 1 秒後の確認時点で振動・音は停止し、通知も消去済み", "OK"),
            ("画面の自動点灯", "このエミュレーター（Wear OS 5）では全画面通知が無効。手首を上げた時に警告を表示する動作", "要実機確認"),
        ],
        [
            "警告画面の自動表示ができない場合に備え、振動・音で気付かせて手首を上げてもらう運用を前提とする",
            "長時間アイドル後に通知が遅れる事象が開発環境（VPN 経由）で発生。現場 Wi-Fi・実機で到達時間を測定する",
            "騒音下での気付きやすさ、電池持ち、Google Play 内部テストからの配信は実機で確認する",
        ],
        note,
    )


if __name__ == "__main__":
    main()
