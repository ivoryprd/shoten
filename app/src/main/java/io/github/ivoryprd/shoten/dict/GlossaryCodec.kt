package io.github.ivoryprd.shoten.dict

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compresses glossary JSON for storage. Glossaries are small and repetitive, so each one is
 * deflated with a preset dictionary built from sample entries of the same dictionary, which
 * shrinks Jitendex to roughly an eighth of its size.
 *
 * Encoded form: one header byte ([RAW], [DEFLATE_DICT] or [DEFLATE]) followed by the payload.
 */
class GlossaryCodec(val presetDictionary: ByteArray?) {

    /** Not thread safe; create one per importing thread. */
    inner class Encoder {
        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        private val buffer = ByteArray(64 * 1024)
        private val out = ByteArrayOutputStream(16 * 1024)

        fun encode(json: String): ByteArray {
            val input = json.toByteArray(Charsets.UTF_8)
            if (input.size < MIN_COMPRESS_SIZE) return byteArrayOf(RAW) + input
            deflater.reset()
            val dict = presetDictionary
            if (dict != null) deflater.setDictionary(dict)
            deflater.setInput(input)
            deflater.finish()
            out.reset()
            out.write(if (dict != null) DEFLATE_DICT.toInt() else DEFLATE.toInt())
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return if (out.size() < input.size + 1) out.toByteArray() else byteArrayOf(RAW) + input
        }

        fun close() {
            deflater.end()
        }
    }

    fun decode(data: ByteArray): String {
        if (data.isEmpty()) return "[]"
        return when (data[0]) {
            RAW -> String(data, 1, data.size - 1, Charsets.UTF_8)
            DEFLATE_DICT -> inflate(data, presetDictionary ?: throw DataFormatException("Missing preset dictionary"))
            DEFLATE -> inflate(data, null)
            else -> throw DataFormatException("Unknown glossary encoding ${data[0]}")
        }
    }

    private fun inflate(data: ByteArray, dict: ByteArray?): String {
        val inflater = Inflater(true)
        try {
            if (dict != null) inflater.setDictionary(dict)
            inflater.setInput(data, 1, data.size - 1)
            val out = ByteArrayOutputStream(data.size * 6)
            val buffer = ByteArray(16 * 1024)
            var fedPadding = false
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n > 0) {
                    out.write(buffer, 0, n)
                } else if (inflater.needsInput()) {
                    // Raw inflate may need one extra byte to detect the end of the stream.
                    if (fedPadding) throw DataFormatException("Truncated glossary data")
                    inflater.setInput(byteArrayOf(0))
                    fedPadding = true
                } else if (inflater.needsDictionary()) {
                    throw DataFormatException("Unexpected dictionary request")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val RAW: Byte = 0
        const val DEFLATE_DICT: Byte = 1
        const val DEFLATE: Byte = 2

        private const val MIN_COMPRESS_SIZE = 48
        const val MAX_DICTIONARY_SIZE = 32 * 1024

        /**
         * Builds a preset dictionary from sample glossaries, spread evenly over the samples.
         * Returns null if there is nothing useful to build from.
         */
        fun trainDictionary(samples: List<String>): ByteArray? {
            val usable = samples.map { it.toByteArray(Charsets.UTF_8) }.filter { it.size in 32..4096 }
            if (usable.isEmpty()) return null
            val totalSize = usable.sumOf { it.size }
            val stride = maxOf(1, Math.ceil(totalSize.toDouble() / MAX_DICTIONARY_SIZE).toInt())
            val out = ByteArrayOutputStream(MAX_DICTIONARY_SIZE)
            var i = 0
            while (i < usable.size) {
                val sample = usable[i]
                if (out.size() + sample.size > MAX_DICTIONARY_SIZE) break
                out.write(sample)
                i += stride
            }
            return if (out.size() >= 256) out.toByteArray() else null
        }
    }
}
