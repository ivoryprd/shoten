package io.github.ivoryprd.shoten.debug

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import io.github.ivoryprd.shoten.ShotenApp
import io.github.ivoryprd.shoten.assist.ShotenAssistService
import io.github.ivoryprd.shoten.dict.DictionaryInstaller
import io.github.ivoryprd.shoten.dict.importer.ImportProgress
import io.github.ivoryprd.shoten.lookup.Language
import io.github.ivoryprd.shoten.ocr.Box
import io.github.ivoryprd.shoten.ocr.CharSuggestions
import io.github.ivoryprd.shoten.ocr.OcrScript
import io.github.ivoryprd.shoten.ocr.await
import io.github.ivoryprd.shoten.util.Json
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only: test hooks for driving the app from adb (the component must be named
 * explicitly, e.g. `am broadcast -n <package>/io.github.ivoryprd.shoten.debug.DebugTriggerReceiver`).
 *  - SHOW_SCREEN_LOOKUP opens the screen lookup without the assist gesture.
 *  - IMPORT_DICTIONARY imports the file at the "path" extra.
 *  - OCR_FILE runs text recognition on the image at the "path" extra and logs the result (with
 *    the "vertical" extra, as vertical text; with "chars", every character's box; with "script"
 *    "zh", the Chinese recognizer).
 *  - SUGGEST logs what the character in the "char" extra may be, read in the "box" (left, top,
 *    right, bottom) of the image at "path", in the "language" ("ja" or "zh"), in vertical text
 *    with the "vertical" extra.
 *  - BENCH renders the lines of the JSON file at the "path" extra ({"locale": "ja", "size": 40,
 *    "lines": [...]}) with the system fonts, recognizes them as the app does and taps every
 *    character's center: writes the truth, what was read and what each tap hit next to the file
 *    (.png and .out.json), to measure how well taps find the characters on real screens.
 *    Optional: "font" (a font file), "color" (#rrggbb), "background" ("dark", "photo:<image
 *    file>", "gradient", "paper"), "effect" ("outline", "shadow", "band": a dark band behind
 *    each line, as subtitles), "dark".
 *  - SNAPSHOT draws the app's windows in software and saves a PNG to the "path" extra (default:
 *    cache/snapshot.png) after the optional "delay" (ms). Useful where screencap returns black
 *    frames, as on emulators without GPU acceleration. Needs hidden API access:
 *    `adb shell settings put global hidden_api_policy 1`.
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> {
                val service = ShotenAssistService.instance
                if (service == null) Log.w(TAG, "Shoten is not the active digital assistant") else service.showScreenLookup()
            }
            ACTION_OCR -> {
                val path = intent.getStringExtra("path") ?: return
                val app = ShotenApp.get(context)
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    Log.e(TAG, "Cannot decode $path")
                    return
                }
                Log.i(TAG, "Bitmap ${bitmap.width}x${bitmap.height} ${bitmap.config}")
                val script = if (intent.getStringExtra("script") == "zh") OcrScript.CHINESE else OcrScript.JAPANESE
                app.ocr.recognizer(script).process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        Log.i(TAG, "ML Kit raw: ${text.textBlocks.size} blocks: ${text.text.replace('\n', '|')}")
                        for (block in text.textBlocks) for (line in block.lines) {
                            val confidences = line.elements.flatMap { e -> e.symbols.map { it.confidence } }
                            Log.i(TAG, "line '${line.text}' box=${line.boundingBox} angle=${line.angle} elements=${line.elements.size} symbols=${confidences.size} confidence=${"%.3f".format(confidences.average())}")
                            for (element in line.elements) {
                                Log.i(TAG, "  element '${element.text}' box=${element.boundingBox} symbols=" +
                                    element.symbols.joinToString(" ") { "${it.text}${it.boundingBox?.toShortString()}" })
                            }
                        }
                        CoroutineScope(Dispatchers.Default).launch {
                            val result = app.ocr.recognize(bitmap, script, intent.getBooleanExtra("vertical", false))
                            Log.i(TAG, "Converted: empty=${result.isEmpty} text=${result.fullText.replace('\n', '|')}")
                            for (block in result.blocks) for (line in block.lines) {
                                Log.i(TAG, "converted '${line.text}' box=${line.box} vertical=${line.vertical}")
                                if (intent.getBooleanExtra("chars", false)) {
                                    for (c in line.chars) Log.i(TAG, "  '${c.text}' ${c.box?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" }}")
                                }
                            }
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "ML Kit failed", it) }
            }
            ACTION_SNAPSHOT -> {
                val out = File(intent.getStringExtra("path") ?: File(context.cacheDir, "snapshot.png").path)
                val pending = goAsync()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        snapshot(context, out, intent.getBooleanExtra("lines", false))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Snapshot failed", e)
                    } finally {
                        pending.finish()
                    }
                }, intent.getIntExtra("delay", 0).toLong())
            }
            ACTION_BENCH -> {
                val path = intent.getStringExtra("path") ?: return
                val app = ShotenApp.get(context)
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        bench(context, app, File(path))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Bench failed", e)
                    }
                }
            }
            ACTION_SUGGEST -> {
                val path = intent.getStringExtra("path") ?: return
                val char = intent.getStringExtra("char") ?: return
                val box = intent.getStringExtra("box")?.split(',')?.map { it.trim().toFloat() } ?: return
                val language = Language.fromCode(intent.getStringExtra("language") ?: "ja")
                val app = ShotenApp.get(context)
                // Not holding the broadcast: the first suggestions can take longer than it may.
                Thread {
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap == null) {
                        Log.e(TAG, "Cannot decode $path")
                        return@Thread
                    }
                    val cell = CharSuggestions.cellOf(bitmap, Box(box[0], box[1], box[2], box[3]))
                    repeat(2) { round ->
                        val start = System.nanoTime()
                        val suggestions = app.charSuggestions.suggest(char, cell, language, intent.getBooleanExtra("vertical", false))
                        Log.i(TAG, "Suggestions for $char (${if (round == 0) "first" else "again"}, ${(System.nanoTime() - start) / 1_000_000} ms): ${suggestions.joinToString("")}")
                    }
                }.start()
            }
            ACTION_IMPORT -> {
                val path = intent.getStringExtra("path") ?: return
                val app = ShotenApp.get(context)
                Thread {
                    try {
                        val installed = DictionaryInstaller(app.dictionaries).install(File(path), ImportProgress.None)
                        Log.i(TAG, "Imported ${installed.title} (${installed.terms} terms)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Import failed", e)
                    }
                }.start()
            }
        }
    }

    private suspend fun bench(context: Context, app: ShotenApp, spec: File) {
        val json = Json.parse(spec.readText()) as Map<*, *>
        val locale = Locale.forLanguageTag(json["locale"] as? String ?: "ja")
        val size = (json["size"] as? Number)?.toFloat() ?: 40f
        val lines = (json["lines"] as List<*>).map { it.toString() }
        val background = json["background"] as? String ?: if (json["dark"] == true) "dark" else "white"
        val dark = background == "dark"
        val effect = json["effect"] as? String
        val textColor = (json["color"] as? String)?.let { Color.parseColor(it) } ?: if (dark) Color.rgb(232, 232, 232) else Color.rgb(20, 20, 20)
        val script = if (locale.language == "zh") OcrScript.CHINESE else OcrScript.JAPANESE
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            textLocale = locale
            color = textColor
            (json["font"] as? String)?.let { typeface = Typeface.createFromFile(it) }
            if (effect == "shadow") setShadowLayer(size * 0.08f, size * 0.05f, size * 0.05f, Color.argb(220, 0, 0, 0))
        }
        val pitch = (size * 2.2f).toInt()
        val bitmap = Bitmap.createBitmap(1080, pitch * lines.size + pitch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, background, bitmap.width, bitmap.height)
        val truth = ArrayList<Any>()
        lines.forEachIndexed { i, marked ->
            // {…} is drawn in blue and <…> in red, like links and highlighted words.
            val styled = SpannableStringBuilder()
            var color: Int? = null
            var spanStart = 0
            for (ch in marked) {
                when (ch) {
                    '{', '<' -> {
                        color = if (ch == '{') Color.rgb(26, 115, 232) else Color.rgb(211, 47, 47)
                        spanStart = styled.length
                    }
                    '}', '>' -> {
                        styled.setSpan(ForegroundColorSpan(color!!), spanStart, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        color = null
                    }
                    else -> styled.append(ch)
                }
            }
            val text = styled.toString()
            val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, 4000).setIncludePad(false).build()
            val x = 40f
            val y = (pitch / 2 + i * pitch).toFloat()
            canvas.save()
            canvas.translate(x, y)
            if (effect == "band") {
                val band = Paint().apply { color = Color.argb(150, 0, 0, 0) }
                canvas.drawRect(-size * 0.3f, -size * 0.25f, layout.getLineWidth(0) + size * 0.3f, layout.height + size * 0.25f, band)
            }
            if (effect == "outline") {
                // Outlined text, as on photos and in videos: a dark stroke around the letters.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.12f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (Color.luminance(textColor) > 0.5f) Color.rgb(10, 10, 10) else Color.WHITE
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = textColor
            }
            layout.draw(canvas)
            canvas.restore()
            val top = y + layout.getLineTop(0)
            val bottom = y + layout.getLineBottom(0)
            val chars = ArrayList<Any>()
            val graphemes = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
            var start = graphemes.first()
            var end = graphemes.next()
            while (end != BreakIterator.DONE) {
                val l = x + layout.getPrimaryHorizontal(start)
                val r = x + if (end >= text.length) layout.getLineWidth(0) else layout.getPrimaryHorizontal(end)
                chars.add(listOf(text.substring(start, end), l.toDouble(), top.toDouble(), r.toDouble(), bottom.toDouble()))
                start = end
                end = graphemes.next()
            }
            truth.add(mapOf("text" to text, "chars" to chars))
        }
        val base = spec.path.removeSuffix(".json")
        File("$base.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // What ML Kit itself reports, for finding where boxes go wrong.
        val raw = ArrayList<Any>()
        val recognized = app.ocr.recognizer(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        for (block in recognized.textBlocks) for (line in block.lines) {
            raw.add(mapOf(
                "text" to line.text,
                "box" to line.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom) },
                "elements" to line.elements.map { e ->
                    mapOf(
                        "text" to e.text,
                        "box" to e.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom) },
                        "symbols" to e.symbols.map { s -> listOf(s.text) + (s.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom, s.confidence.toDouble()) } ?: emptyList()) },
                    )
                },
            ))
        }
        val result = app.ocr.recognize(bitmap, script, false)
        val read = result.blocks.flatMap { it.lines }.map { line ->
            mapOf("text" to line.text, "chars" to line.chars.map { c -> listOf(c.text) + (c.box?.let { listOf(it.left, it.top, it.right, it.bottom).map(Float::toDouble) } ?: emptyList()) })
        }
        // Taps at every character's center, with the slop of a finger on a phone (see ScreenshotView).
        val slop = context.resources.displayMetrics.density * 14f
        val hits = truth.map { line ->
            ((line as Map<*, *>)["chars"] as List<*>).map { c ->
                val (t, l, top, r, b) = c as List<*>
                val hit = result.hitTest(((l as Double + r as Double) / 2).toFloat(), ((top as Double + b as Double) / 2).toFloat(), slop)
                val ch = hit?.let { result.charAt(it) }
                listOf(t, ch?.text, ch?.box?.let { listOf(it.left, it.right).map(Float::toDouble) })
            }
        }
        File("$base.out.json").writeText(Json.write(mapOf("size" to size.toDouble(), "truth" to truth, "raw" to raw, "read" to read, "hits" to hits)))
        bitmap.recycle()
        Log.i(TAG, "Bench done: $base.out.json")
    }

    /** Draws a BENCH background: white, dark, a photo (cropped to fill), a colorful gradient or paper. */
    private fun drawBackground(canvas: Canvas, background: String, w: Int, h: Int) {
        when {
            background == "dark" -> canvas.drawColor(Color.rgb(24, 24, 28))
            background.startsWith("photo:") -> {
                val photo = BitmapFactory.decodeFile(background.removePrefix("photo:"))
                if (photo == null) {
                    canvas.drawColor(Color.GRAY)
                    return
                }
                val scale = max(w.toFloat() / photo.width, h.toFloat() / photo.height)
                val dw = photo.width * scale
                val dh = photo.height * scale
                canvas.drawBitmap(photo, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                photo.recycle()
            }
            background == "gradient" -> {
                val shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(Color.rgb(255, 204, 128), Color.rgb(244, 143, 177), Color.rgb(129, 212, 250), Color.rgb(197, 225, 165)), null, Shader.TileMode.CLAMP)
                canvas.drawPaint(Paint().apply { this.shader = shader })
            }
            background == "paper" -> {
                // Beige with fibers and blotches.
                canvas.drawColor(Color.rgb(236, 226, 204))
                val random = java.util.Random(7)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                repeat(4000) {
                    p.color = Color.argb(30 + random.nextInt(40), 120 + random.nextInt(60), 100 + random.nextInt(50), 70 + random.nextInt(40))
                    p.strokeWidth = 1f + random.nextFloat() * 1.5f
                    val x0 = random.nextFloat() * w
                    val y0 = random.nextFloat() * h
                    canvas.drawLine(x0, y0, x0 + random.nextFloat() * 30 - 15, y0 + random.nextFloat() * 30 - 15, p)
                }
                repeat(60) {
                    p.color = Color.argb(20, 150, 120, 80)
                    canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h, 20f + random.nextFloat() * 80f, p)
                }
            }
            else -> canvas.drawColor(Color.WHITE)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi") // Debug builds only.
    private fun snapshot(context: Context, out: File, dumpLines: Boolean) {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>)
            .filterIsInstance<View>()
            // Windows of higher types (such as the assistant's) are above application windows.
            .sortedBy { (it.layoutParams as? WindowManager.LayoutParams)?.type ?: 0 }
        val size = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
        val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(96, 96, 96))
        val location = IntArray(2)
        for (view in views) {
            if (!view.isShown || view.width == 0 || view.height == 0) continue
            view.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            try {
                view.draw(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot draw $view", e)
            }
            canvas.restore()
        }
        // Written to a temporary file first, so that the snapshot appears only when it is complete.
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(out)
        Log.i(TAG, "Snapshot of ${views.size} windows saved to $out")
        if (dumpLines) views.forEach { dumpLines(it) }
    }

    /** Logs the line metrics of all text views, for checking spacing. */
    private fun dumpLines(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dumpLines(view.getChildAt(i))
        } else if (view is TextView && view.isShown) {
            val layout = view.layout ?: return
            for (line in 0 until layout.lineCount) {
                val text = layout.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line)).toString().replace('\n', '|')
                Log.i(TAG, "line ${layout.getLineTop(line)}..${layout.getLineBottom(line)} ascent=${layout.getLineAscent(line)} descent=${layout.getLineDescent(line)} '${text.take(40)}'")
            }
        }
    }

    companion object {
        private const val TAG = "DebugTrigger"
        private const val ACTION_SNAPSHOT = "io.github.ivoryprd.shoten.debug.SNAPSHOT"
        private const val ACTION_SHOW = "io.github.ivoryprd.shoten.debug.SHOW_SCREEN_LOOKUP"
        private const val ACTION_IMPORT = "io.github.ivoryprd.shoten.debug.IMPORT_DICTIONARY"
        private const val ACTION_OCR = "io.github.ivoryprd.shoten.debug.OCR_FILE"
        private const val ACTION_SUGGEST = "io.github.ivoryprd.shoten.debug.SUGGEST"
        private const val ACTION_BENCH = "io.github.ivoryprd.shoten.debug.BENCH"
    }
}
