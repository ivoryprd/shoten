# Yomitan dictionary support

Shoten imports dictionaries in the [Yomitan](https://github.com/yomidevs/yomitan) format (formerly
Yomichan), the de facto standard for free Japanese dictionaries (Jitendex, JMdict, KANJIDIC, JPDB
frequencies, Kanjium pitch accents, …) and increasingly for Chinese and other languages. This document
describes what is implemented and the plan for the rest. The format is specified by the JSON schemas in
[yomitan/ext/data/schemas](https://github.com/yomidevs/yomitan/tree/master/ext/data/schemas).

## Implemented

### Import (`dict/importer/YomitanImporter.kt`)

| Part of the format | Status |
| --- | --- |
| `index.json` (format/version 1–3, title, revision, author, url, description, attribution, `sourceLanguage`, `targetLanguage`, `sequenced`) | ✅ |
| `index.json` update info (`isUpdatable`, `indexUrl`, `downloadUrl`) | ✅ used by *Check for updates* |
| `index.json` `tagMeta` (v1/v2 tag definitions) | ✅ |
| `term_bank_N.json` v1 (`[expression, reading, tags, rules, score, ...glossary]`) and v3 (`[expression, reading, tags, rules, score, glossary[], sequence, termTags]`) | ✅ |
| `term_meta_bank_N.json`: `freq` (number, string, `{value, displayValue}`, `{reading, frequency}`), `pitch`, `ipa` | ✅ stored; `freq` and `pitch` displayed |
| `kanji_bank_N.json` v1 and v3 (readings, tags, meanings, stats) | ✅ |
| `kanji_meta_bank_N.json` | ✅ stored, not displayed yet |
| `tag_bank_N.json` | ✅ (tag names with non-breaking spaces, as in Jitendex, are matched) |
| `styles.css` | ✅ stored and applied (subset, see below) |
| Media (images referenced by structured content) | ✅ stored (≤ 4 MB each, 64 MB per dictionary) |
| Dictionaries inside a subfolder of the zip | ✅ |
| Language detection when `sourceLanguage` is missing | ✅ from the headwords (kana → Japanese, hanzi → Chinese); can be changed per dictionary |

Import streams every bank through a pull parser, so memory use does not depend on the file size.
Each dictionary is written to its own SQLite database (`files/dictionaries/<id>.db`) and only becomes
visible once it is complete, so a failed or cancelled import leaves nothing behind and deleting a
dictionary is instant. Glossaries are compressed with raw deflate and a preset dictionary sampled from
the first 2,000 entries of the same dictionary (Jitendex: 466 MB of glossary JSON → a 109 MB
database including indexes).

### Lookup (`lookup/`)

- Every prefix of the scanned text is tried, longest first; the scanned text stops at whitespace and
  sentence punctuation (24 characters for Japanese, 16 for Chinese).
- Japanese prefixes are deinflected with Shoten's own rule table (`Deinflector.kt`), and candidates are
  validated against the entry's `rules` (`v1`, `v5`, `vk`, `vs`, `vz`, `adj-i`), like Yomitan.
- Text variants: as scanned, katakana → hiragana, and OCR look-alike fixes (一 → ー, 口 → ロ, 力 → カ …
  next to katakana); half-width katakana are normalized first.
- Headwords and readings are both indexed, so kana-only text finds kanji headwords.
- Glossary items of the form `[term, [reasons]]` (Jitendex uses them for rare and old spellings) are
  followed to the entry they point to, preferring the entry with the matching sequence number.
- Results are sorted by matched length, number of transformations, text variant, dictionary order and
  score, and grouped by headword + reading across dictionaries.
- `freq` and `pitch` metadata of the headword is attached (filtered by reading when the data has one).
- Kanji dictionaries are queried for the first character in Japanese mode.

### Rendering (`render/`)

Structured content is rendered to native Android text spans (no WebView):

| Element | Rendering |
| --- | --- |
| `div`, `p`, `ul`, `ol`, `li`, `details`, `summary`, `blockquote`, headings | paragraphs; list markers from `listStyleType` (strings such as `"①"`, `disc`, `circle`, `square`, `decimal`, `lower-alpha`), nested lists |
| `ruby` / `rt` / `rp` | furigana drawn above the base text |
| `span` | inline styles; short spans with a background color become rounded chips (tags) |
| `a` | `?query=…` links start a new lookup (with back navigation); `http(s)` links open the browser |
| `table`, `tr`, `td`, `th` (`colSpan`) | real tables (scrollable); Jitendex's empty form cells get their ◇/△/✕/古/旧/▽ symbols |
| `img` | decoded from the stored media with the platform decoders (PNG, JPEG, WebP, GIF; AVIF on Android 12+) |
| `br` | line break |
| `lang` attribute | locale span, so kanji are drawn with Japanese or Chinese glyph shapes |
| `title` attribute | tapping the element shows it |
| `data` attributes | exposed to CSS as `data-sc-<key>` |

Supported style properties (inline `style` objects and `styles.css`): `color`, `background-color`,
`font-size`, `font-weight`, `font-style`, `text-decoration(-line)`, `vertical-align` (super/sub),
`text-align` (right/center), `list-style-type`, `border-color`/`border-style` (shown as a bar next to
the block, used for Jitendex's notes and examples), `display: none`.

The CSS engine understands compound selectors (tag name plus attribute conditions such as
`span[data-sc-content="part-of-speech-info"]`) with normal cascade order. Rules using combinators
(`a > b`, `a b`), pseudo-classes/elements, nesting (`&`) or `@`-rules are skipped.

Plain string glossaries are shown as a numbered list; CC-CEDICT-style numbered pinyin in definitions
(`[zhong1 wen2]`) is converted to tone marks.

## Plan

### Phase 1 — rendering fidelity

1. Descendant and child combinators in the CSS engine (`td[data-sc-class="form-pri"] > span`,
   `li[data-sc-content="sense"] ul`) and CSS nesting, evaluated against the element stack during
   rendering. This removes the Jitendex-specific form symbols in favor of its own `::before` rules
   (add `content` support for `::before`/`::after`).
2. `var()`, `color-mix()` and theme variables (`--text-color`, `--background-color`) mapped to the
   app's light/dark colors.
3. Collapsible `details` (collapsed by default when `open` is absent) and image `collapsible` /
   `collapsed` flags; `imageRendering`, `verticalAlign` and `sizeUnits: "em"` for inline images.
4. Kanji meta (`kanji_meta_bank` frequencies) and IPA (`ipa` term meta) display; pitch accent graphs
   instead of the `ꜜ [n]` notation.

### Phase 2 — lookup behavior

1. Frequency-based sorting: use `frequencyMode` (`occurrence-based` / `rank-based`) from `index.json` to
   rank results with the user's chosen frequency dictionary, as Yomitan's *Sort by frequency* option.
2. Yomitan's "merge" grouping for `sequenced` dictionaries (one card per JMdict sequence, listing all
   spellings), as an option next to the current headword + reading grouping.
3. More text preprocessors: collapsing emphatic sequences (すっっっごい), long-vowel replacement
   (すげー → すごい), kanji variant normalization (舊字体 → 新字体), full-width/half-width
   alphanumerics.
4. Deinflection rules for Chinese are not needed, but Yomitan dictionaries for other languages
   (Korean, etc.) would need their own transformation tables; the engine already isolates the
   Japanese rules behind the language setting.

### Phase 3 — dictionary management

1. Import several dictionaries at once and Yomitan's exported dictionary collections.
2. Scheduled update checks for updatable dictionaries (currently manual).
3. Per-dictionary options: hide from screen lookups, collapse by default, maximum entries.
4. Integrity checks against the schemas with clear error messages (currently invalid rows are skipped
   and invalid JSON aborts the import with the file name).

### Phase 4 — beyond dictionaries

1. Anki export through the AnkiDroid API (fields: expression, reading, glossary HTML, sentence from
   the OCR line, screenshot crop).
2. Audio from Yomitan-compatible audio sources.
3. A `local` audio/media server is not planned; media stays in the per-dictionary database.
