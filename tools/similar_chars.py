#!/usr/bin/env python3
"""Generates the tables of visually similar characters that the app offers when correcting
recognized text (app/src/main/assets/similar/ja.txt and zh.txt).

The characters are the kana and the most common kanji (by how many Jitendex headwords use them)
and the most common hanzi (by CC-CEDICT headwords). Each one's neighbors are the characters whose
glyphs look most alike: blurred shapes and stroke directions compared, as rendered by a Japanese
and a Chinese font. Hand-made groups come first: small and full-size kana, voiced kana, kana
and kanji twins (ロ and 口), punctuation.

Usage: similar_chars.py JITENDEX_DIR CEDICT_FILE OUT_DIR [JA_FONT ZH_FONT]
  JITENDEX_DIR  the unzipped Jitendex (Yomitan format) dictionary, with term_bank_*.json
  CEDICT_FILE   cedict_1_0_ts_utf-8_mdbg.txt(.gz)
  JA_FONT       default: IPAGothic (fonts-ipafont-gothic)
  ZH_FONT       default: WenQuanYi Zen Hei (fonts-wqy-zenhei)
Needs numpy and Pillow.
"""
import collections
import glob
import gzip
import json
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

KANJI = 3000     # most common kanji
HANZI = 5000     # most common hanzi (simplified and traditional)
NEIGHBORS = 6    # visual neighbors per character
CAP = 10         # most characters in a list

SIZE_PAIRS = 'ぁあ ぃい ぅう ぇえ ぉお っつ ゃや ゅゆ ょよ ゎわ ゕか ゖけ ァア ィイ ゥウ ェエ ォオ ッツ ャヤ ュユ ョヨ ヮワ ヵカ ヶケ'
VOICED = ('かが きぎ くぐ けげ こご さざ しじ すず せぜ そぞ ただ ちぢ つづ てで とど はばぱ ひびぴ ふぶぷ へべぺ ほぼぽ うゔ '
          'カガ キギ クグ ケゲ コゴ サザ シジ スズ セゼ ソゾ タダ チヂ ツヅ テデ トド ハバパ ヒビピ フブプ ヘベペ ホボポ ウヴ')
TWINS = 'へヘ べベ ぺペ りリ かカ がガ せセ もモ やヤ 口ロ 力カ 工エ 夕タ 卜ト 二ニ 八ハ 才オ 千チ 匕ヒ 三ミ 一ー'
JA_GROUPS = SIZE_PAIRS.split() + VOICED.split() + TWINS.split() + [
    '。.○o゜', '、,`', 'ー一-―〜', '・.', '「｢J', '」｣L', '！!', '？?', '（(', '）)', '：:',
]
ZH_GROUPS = ['。.○o', '，,、', '、,', '一-―', '！!', '？?', '（(', '）)', '：:', '；;', '「｢', '」｣', '已己巳', '日曰', '未末', '土士', '人入八']

SIZE = 64


def is_han(c):
    cp = ord(c)
    return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF


def by_frequency(jitendex, cedict):
    ja = collections.Counter()
    for path in glob.glob(jitendex + '/term_bank_*.json'):
        with open(path, encoding='utf-8') as f:
            for entry in json.load(f):
                ja.update(c for c in set(entry[0]) if is_han(c))
    zh = collections.Counter()
    opener = gzip.open if cedict.endswith('.gz') else open
    with opener(cedict, 'rt', encoding='utf-8') as f:
        for line in f:
            parts = line.split(' ', 2)
            if line.startswith('#') or len(parts) < 3:
                continue
            zh.update(c for c in set(parts[0] + parts[1]) if is_han(c))
    return [c for c, _ in ja.most_common()], [c for c, _ in zh.most_common()]


def render(ch, font):
    img = Image.new('L', (SIZE, SIZE), 0)
    # Placed by the font's em box (not the ink), so small kana stay small.
    ImageDraw.Draw(img).text((SIZE / 2, SIZE / 2), ch, font=font, fill=255, anchor='mm')
    return np.asarray(img, dtype=np.float32) / 255.0


def blur(a, r):
    return np.asarray(Image.fromarray((a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r)), dtype=np.float32) / 255.0


def shape(a):
    small = blur(a, 3).reshape(16, 4, 16, 4).mean(axis=(1, 3)).ravel()
    small = small - small.mean()
    n = np.linalg.norm(small)
    return small / n if n > 0 else small


def strokes(a):
    gy, gx = np.gradient(blur(a, 1))
    mag = np.hypot(gx, gy)
    ang = (np.arctan2(gy, gx) % np.pi) / np.pi * 6  # 6 directions
    planes = []
    for k in range(6):
        w = np.clip(1 - np.abs(((ang - k + 3) % 6) - 3), 0, 1) * mag
        top = w.max() + 1e-6
        planes.append((blur(w / top, 2) * top).reshape(8, 8, 8, 8).mean(axis=(1, 3)).ravel())
    f = np.sqrt(np.concatenate(planes))
    n = np.linalg.norm(f)
    return f / n if n > 0 else f


def table(chars, font, groups):
    tofu = render('\U0010FFFD', font)
    glyphs = {}
    for c in dict.fromkeys(chars):
        a = render(c, font)
        if a.sum() > 0 and not np.array_equal(a, tofu):
            glyphs[c] = a
    chars = list(glyphs)
    index = {c: i for i, c in enumerate(chars)}
    features = np.stack([np.concatenate([shape(a) * 0.6, strokes(a) * 0.8]) for a in glyphs.values()]).astype(np.float32)
    similarity = features @ features.T
    np.fill_diagonal(similarity, -1)
    neighbors = {c: [chars[j] for j in np.argsort(-similarity[i])[:NEIGHBORS]] for i, c in enumerate(chars)}
    # If b looks like a, a looks like b.
    for c in chars:
        for n in list(neighbors[c]):
            if c not in neighbors[n]:
                neighbors[n].append(c)
    result = {}
    for c in set(neighbors) | {ch for g in groups for ch in g}:
        grouped = [ch for g in groups if c in g for ch in g if ch != c]
        visual = sorted(neighbors.get(c, []), key=lambda n: -similarity[index[c], index[n]])
        merged = list(dict.fromkeys(grouped + visual))[:CAP]
        if merged:
            result[c] = merged
    return result


def write(path, t):
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# Visually similar characters: a character, a tab, and the characters it may be mistaken for.\n')
        f.write('# Generated by tools/similar_chars.py; do not edit by hand.\n')
        for c in sorted(t):
            f.write(c + '\t' + ''.join(t[c]) + '\n')


def main():
    jitendex, cedict, out = sys.argv[1:4]
    ja_font = sys.argv[4] if len(sys.argv) > 4 else '/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf'
    zh_font = sys.argv[5] if len(sys.argv) > 5 else '/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc'
    kanji, hanzi = by_frequency(jitendex, cedict)
    kana = [chr(c) for c in list(range(0x3041, 0x3097)) + list(range(0x30A1, 0x30FB)) + [0x30FC, 0x309D, 0x309E, 0x30FD, 0x30FE]]
    write(out + '/ja.txt', table(kana + kanji[:KANJI], ImageFont.truetype(ja_font, 56), JA_GROUPS))
    write(out + '/zh.txt', table(hanzi[:HANZI], ImageFont.truetype(zh_font, 56), ZH_GROUPS))


if __name__ == '__main__':
    main()
