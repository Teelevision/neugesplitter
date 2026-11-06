package eu.teele.neugesplitter.domain.category

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp

/**
 * Guesses a built-in category from an entry's description (EN-13): a linear classifier over the
 * average of the description's word vectors, German and English ones aligned into one space, and
 * the average of its hashed character n-grams, which carry what no word vector does, such as shop
 * names and typos (decisions.md). `:category-model` trains it into the layout [encode] writes.
 */
class CategoryModel(buffer: ByteBuffer) {

    private val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    /** The categories the model tells apart, in the order of [probabilities]. */
    val labels: List<BuiltInCategory?>

    /** How likely a guess must be to be made. */
    val threshold: Float

    private val words: Int
    private val dimension: Int
    private val buckets: Int
    private val gramDimension: Int
    private val shortest: Int
    private val longest: Int
    private val slots: Int
    private val wordScale: Float
    private val gramScale: Float
    private val offsets: Int
    private val text: Int
    private val table: Int
    private val wordVectors: Int
    private val gramVectors: Int
    private val output: FloatArray
    private val bias: FloatArray

    init {
        require(data.getInt(0) == MAGIC) { "not a category model" }
        var at = 4
        fun int() = data.getInt(at).also { at += 4 }
        fun float() = data.getFloat(at).also { at += 4 }
        val count = int()
        words = int()
        dimension = int()
        buckets = int()
        gramDimension = int()
        shortest = int()
        longest = int()
        slots = int()
        threshold = float()
        wordScale = float()
        gramScale = float()
        labels =
            List(count) {
                val bytes = ByteArray(int()) { index -> data.get(at + index) }
                at += bytes.size
                val key = String(bytes, Charsets.UTF_8)
                BuiltInCategory.entries.firstOrNull { it.key == key }
            }
        offsets = at
        at += (words + 1) * 4
        text = at
        at += data.getInt(offsets + words * 4)
        table = at
        at += slots * 4
        wordVectors = at
        at += words * dimension
        gramVectors = at
        at += buckets * gramDimension
        val features = dimension + gramDimension
        output = FloatArray(count * features) { float() }
        bias = FloatArray(count) { float() }
    }

    /** How likely the description is to be of each of [labels]. */
    fun probabilities(description: String): FloatArray {
        val features = FloatArray(dimension + gramDimension)
        var known = 0
        var grams = 0
        for (word in words(description)) {
            val index = index(word)
            if (index >= 0) {
                known++
                val row = wordVectors + index * dimension
                for (d in 0 until dimension) features[d] += data.get(row + d) * wordScale
            }
            for (bucket in ngrams(word, shortest, longest, buckets)) {
                grams++
                val row = gramVectors + bucket * gramDimension
                for (d in 0 until gramDimension) {
                    features[dimension + d] += data.get(row + d) * gramScale
                }
            }
        }
        for (d in 0 until dimension) if (known > 0) features[d] /= known
        for (d in dimension until features.size) if (grams > 0) features[d] /= grams
        return softmax(FloatArray(labels.size) { dot(it, features) + bias[it] })
    }

    /**
     * The category the description most likely is of, if likely enough. A transfer is told by its
     * kind, not its description, so it is never guessed; a description that looks like one guesses
     * nothing.
     */
    fun guess(description: String): BuiltInCategory? {
        val probabilities = probabilities(description)
        val best =
            labels.indices
                .filter { labels[it] != null && labels[it] != BuiltInCategory.TRANSFER }
                .maxByOrNull { probabilities[it] } ?: return null
        return labels[best].takeIf { probabilities[best] >= threshold }
    }

    private fun dot(label: Int, features: FloatArray): Float {
        var sum = 0f
        val row = label * features.size
        for (d in features.indices) sum += output[row + d] * features[d]
        return sum
    }

    private fun index(word: String): Int {
        val bytes = word.encodeToByteArray()
        var slot = fnv(bytes) and (slots - 1)
        while (true) {
            val entry = data.getInt(table + slot * 4)
            if (entry == 0) return -1
            if (matches(entry - 1, bytes)) return entry - 1
            slot = (slot + 1) and (slots - 1)
        }
    }

    private fun matches(index: Int, bytes: ByteArray): Boolean {
        val start = data.getInt(offsets + index * 4)
        if (data.getInt(offsets + (index + 1) * 4) - start != bytes.size) return false
        return bytes.indices.all { data.get(text + start + it) == bytes[it] }
    }

    /**
     * What `:category-model` trained. Vectors are signed bytes, which their scale turns back into
     * numbers; [output] holds a row of word then n-gram weights for each label.
     */
    class Parts(
        val labels: List<String>,
        val threshold: Float,
        val words: List<String>,
        val dimension: Int,
        val wordVectors: ByteArray,
        val wordScale: Float,
        val buckets: Int,
        val gramDimension: Int,
        val shortest: Int,
        val longest: Int,
        val gramVectors: ByteArray,
        val gramScale: Float,
        val output: FloatArray,
        val bias: FloatArray,
    )

    companion object {
        private const val MAGIC = 0x3143474e // "NGC1"

        fun encode(parts: Parts): ByteArray {
            val encoded = parts.words.map { it.encodeToByteArray() }
            val slots = Integer.highestOneBit(maxOf(1, encoded.size)) * 2
            val table = IntArray(slots)
            encoded.forEachIndexed { index, bytes ->
                var slot = fnv(bytes) and (slots - 1)
                while (table[slot] != 0) slot = (slot + 1) and (slots - 1)
                table[slot] = index + 1
            }
            val labels = parts.labels.map { it.encodeToByteArray() }
            val size =
                4 * 12 +
                    labels.sumOf { 4 + it.size } +
                    (encoded.size + 1) * 4 +
                    encoded.sumOf { it.size } +
                    slots * 4 +
                    parts.wordVectors.size +
                    parts.gramVectors.size +
                    (parts.output.size + parts.bias.size) * 4
            val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MAGIC)
            out.putInt(labels.size)
            out.putInt(encoded.size)
            out.putInt(parts.dimension)
            out.putInt(parts.buckets)
            out.putInt(parts.gramDimension)
            out.putInt(parts.shortest)
            out.putInt(parts.longest)
            out.putInt(slots)
            out.putFloat(parts.threshold)
            out.putFloat(parts.wordScale)
            out.putFloat(parts.gramScale)
            labels.forEach { out.putInt(it.size).put(it) }
            var offset = 0
            for (bytes in encoded) {
                out.putInt(offset)
                offset += bytes.size
            }
            out.putInt(offset)
            encoded.forEach { out.put(it) }
            table.forEach { out.putInt(it) }
            out.put(parts.wordVectors)
            out.put(parts.gramVectors)
            parts.output.forEach { out.putFloat(it) }
            parts.bias.forEach { out.putFloat(it) }
            return out.array()
        }
    }
}

/**
 * The words of [text] as the model knows them: in NFKC and lower case, split at everything but
 * letters and digits.
 */
fun words(text: String): List<String> {
    val normal = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var i = 0
    while (i < normal.length) {
        val c = normal.codePointAt(i)
        if (Character.isLetterOrDigit(c)) {
            word.appendCodePoint(c)
        } else if (word.isNotEmpty()) {
            words += word.toString()
            word.clear()
        }
        i += Character.charCount(c)
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}

/**
 * The buckets of [word] marked at both ends, whole and in n-grams of [shortest] to [longest] code
 * points; [buckets] is a power of two.
 */
fun ngrams(word: String, shortest: Int, longest: Int, buckets: Int): IntArray {
    val marked = "<$word>"
    val points = marked.codePoints().toArray()
    val grams = mutableListOf(marked)
    for (start in points.indices) {
        for (n in shortest..longest) {
            if (start + n > points.size) break
            if (n == points.size) continue
            grams += String(points, start, n)
        }
    }
    return IntArray(grams.size) { fnv(grams[it].encodeToByteArray()) and (buckets - 1) }
}

/** 32-bit FNV-1a. */
fun fnv(bytes: ByteArray): Int {
    var hash = 0x811c9dc5.toInt()
    for (byte in bytes) {
        hash = hash xor (byte.toInt() and 0xff)
        hash *= 0x01000193
    }
    return hash
}

private fun softmax(logits: FloatArray): FloatArray {
    val max = logits.max()
    val exps = FloatArray(logits.size) { exp(logits[it] - max) }
    val sum = exps.sum()
    return FloatArray(exps.size) { exps[it] / sum }
}
