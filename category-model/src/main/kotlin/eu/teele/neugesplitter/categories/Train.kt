package eu.teele.neugesplitter.categories

import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.CategoryModel
import eu.teele.neugesplitter.domain.category.ngrams
import eu.teele.neugesplitter.domain.category.words
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** The most frequent words kept of each language, besides those the training terms use. */
private const val WORDS = 40_000
private const val DIMENSION = 100
private const val BUCKETS = 1 shl 15
private const val GRAM_DIMENSION = 32
private const val SHORTEST = 3
private const val LONGEST = 5
private const val EPOCHS = 40
private const val RATE = 0.2f
private const val SEED = 7

/** How many guesses on the held-out terms must be right, which sets the threshold. */
private const val PRECISION = 0.9

/** Every tenth term of each category is held out to measure the model on. */
private const val HELD_OUT = 10

private val LANGUAGES = listOf("en", "de")

private val PROBES =
    listOf(
        "Zahnarzt",
        "Kinokarten",
        "Rewe",
        "Hundefutter",
        "Miete Januar",
        "Flug nach Lissabon",
        "Pizza",
        "Netflix Abo",
        "Stromrechnung",
        "Geschenk für Oma",
        "dentist",
        "movie tickets",
        "dog food",
        "rent",
        "flight to Lisbon",
        "electricity bill",
        "birthday present",
        "Wocheneinkauf",
        "Tankstelle",
        "Kletterhalle",
    )

private class Sample(val text: String, val language: String, val label: Int)

/**
 * Trains the category guess (EN-13, decisions.md) from the aligned fastText vectors of [LANGUAGES]
 * and the training terms, and writes the model `CategoryModel` reads.
 *
 * Arguments: the directory holding `wiki.<language>.vec`, the training terms' directory, the model
 * to write.
 */
fun main(args: Array<String>) {
    val (vectors, training, output) = args.map(::File)
    val labels = BuiltInCategory.entries
    val samples = samples(training, labels)
    val used = samples.flatMap { words(it.text) }.toSet()
    val embedding = embed(LANGUAGES.map { read(File(vectors, "wiki.$it.vec"), used) })
    println("${embedding.words.size} words, ${samples.size} terms")

    val test = samples.filterIndexed { index, _ -> index % HELD_OUT == 0 }
    val train = samples.filterIndexed { index, _ -> index % HELD_OUT != 0 }
    val held = CategoryModel(ByteBuffer.wrap(CategoryModel.encode(fit(train, embedding, 0f))))
    val threshold = report(held, test)
    val seen = train.flatMap { words(it.text) }.toSet()
    val unseen = test.filter { words(it.text).none(seen::contains) }
    println(
        "  of ${unseen.size} with no word trained on: %.1f%%"
            .format(unseen.count { held.top(it.text) == it.label } * 100.0 / unseen.size)
    )

    val model = CategoryModel.encode(fit(samples, embedding, threshold))
    output.writeBytes(model)
    println("Wrote ${output.path}, ${model.size / 1024} KiB")
    val final = CategoryModel(ByteBuffer.wrap(model))
    for (probe in PROBES) {
        val probabilities = final.probabilities(probe)
        val best = probabilities.indices.maxBy { probabilities[it] }
        println(
            "%-22s %-24s %.2f -> %s"
                .format(probe, final.labels[best]?.key, probabilities[best], final.guess(probe))
        )
    }
}

private fun samples(directory: File, labels: List<BuiltInCategory>): List<Sample> =
    labels.flatMapIndexed { label, category ->
        File(directory, "${category.key}.csv")
            .readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map {
                val comma = it.indexOf(',')
                val term = it.substring(comma + 1).trim()
                val text =
                    if (term.length > 1 && term.startsWith('"') && term.endsWith('"'))
                        term.substring(1, term.length - 1).replace("\"\"", "\"")
                    else term
                Sample(text, it.substring(0, comma), label)
            }
    }

/**
 * The first [WORDS] words of a `.vec` file, which lists the most frequent first, and any other of
 * [used] in it, each to unit length. Only words the model can be asked about are kept.
 */
private fun read(file: File, used: Set<String>): Map<String, FloatArray> {
    val vectors = mutableMapOf<String, FloatArray>()
    var kept = 0
    file.useLines { lines ->
        lines.drop(1).forEach { line ->
            val word = line.substringBefore(' ')
            if (words(word) != listOf(word) || word in vectors) return@forEach
            if (kept >= WORDS && word !in used) return@forEach
            if (kept < WORDS) kept++
            val values = line.substring(word.length + 1).trim().split(' ')
            vectors[word] = unit(FloatArray(values.size) { values[it].toFloat() })
        }
    }
    return vectors
}

private class Embedding(val words: List<String>, val vectors: ByteArray, val scale: Float) {
    private val index = words.withIndex().associate { it.value to it.index }

    /** The average of the vectors of [text]'s words that have one, as the model reads them. */
    fun average(text: String): FloatArray {
        val sum = FloatArray(DIMENSION)
        val known = words(text).mapNotNull(index::get)
        for (i in known) for (d in 0 until DIMENSION) sum[d] += vectors[i * DIMENSION + d] * scale
        if (known.isNotEmpty()) for (d in sum.indices) sum[d] /= known.size
        return sum
    }
}

/**
 * Both languages' vectors in one vocabulary, a word both have averaged, which their alignment
 * allows; then cut down to their [DIMENSION] principal components, to unit length again, and to
 * bytes.
 */
private fun embed(languages: List<Map<String, FloatArray>>): Embedding {
    val merged = linkedMapOf<String, FloatArray>()
    for (language in languages) {
        for ((word, vector) in language) {
            merged.merge(word, vector) { a, b -> FloatArray(a.size) { a[it] + b[it] } }
        }
    }
    val words = merged.keys.toList()
    val matrix = words.map { unit(merged.getValue(it)) }
    val size = matrix.first().size
    val mean = DoubleArray(size)
    for (vector in matrix) for (i in 0 until size) mean[i] += vector[i].toDouble() / matrix.size
    val covariance = Array(size) { DoubleArray(size) }
    val centred = DoubleArray(size)
    for (vector in matrix) {
        for (i in 0 until size) centred[i] = vector[i] - mean[i]
        for (i in 0 until size) {
            val row = covariance[i]
            val c = centred[i]
            for (j in i until size) row[j] += c * centred[j]
        }
    }
    for (i in 0 until size) for (j in 0 until i) covariance[i][j] = covariance[j][i]
    val (values, axes) = eigen(covariance)
    val order = values.indices.sortedByDescending { values[it] }.take(DIMENSION)
    println("Kept %.1f%% of the variance".format(order.sumOf { values[it] } / values.sum() * 100))

    val projected =
        matrix.map { vector ->
            unit(
                FloatArray(DIMENSION) { k ->
                    val axis = order[k]
                    var sum = 0.0
                    for (i in 0 until size) sum += (vector[i] - mean[i]) * axes[i][axis]
                    sum.toFloat()
                }
            )
        }
    val scale = clip(projected.flatMap { it.asList() }) / 127
    val bytes = ByteArray(words.size * DIMENSION)
    projected.forEachIndexed { w, vector ->
        for (d in 0 until DIMENSION) bytes[w * DIMENSION + d] = quantized(vector[d], scale)
    }
    return Embedding(words, bytes, scale)
}

/**
 * Trains the n-gram vectors and the output layer by stochastic gradient descent on the softmax
 * loss, with the word vectors fixed, so that every word of the vocabulary keeps its place among the
 * others whether or not a term uses it.
 */
private fun fit(
    samples: List<Sample>,
    embedding: Embedding,
    threshold: Float,
): CategoryModel.Parts {
    val labels = BuiltInCategory.entries.size
    val features = DIMENSION + GRAM_DIMENSION
    val random = Random(SEED)
    val grams =
        FloatArray(BUCKETS * GRAM_DIMENSION) { (random.nextFloat() * 2 - 1) / GRAM_DIMENSION }
    val output = FloatArray(labels * features)
    val bias = FloatArray(labels)
    val averages = samples.map { embedding.average(it.text) }
    val buckets =
        samples.map { sample ->
            words(sample.text).flatMap { ngrams(it, SHORTEST, LONGEST, BUCKETS).asList() }
        }
    val steps = EPOCHS * samples.size
    var step = 0
    val h = FloatArray(features)
    val g = FloatArray(labels)
    val back = FloatArray(GRAM_DIMENSION)
    repeat(EPOCHS) {
        for (s in samples.indices.shuffled(random)) {
            val rate = RATE * (1 - step++.toFloat() / steps)
            val ids = buckets[s]
            averages[s].copyInto(h)
            h.fill(0f, DIMENSION)
            for (id in ids) for (d in 0 until GRAM_DIMENSION) h[DIMENSION + d] +=
                grams[id * GRAM_DIMENSION + d]
            if (ids.isNotEmpty()) for (d in DIMENSION until features) h[d] /= ids.size
            for (l in 0 until labels) {
                var z = bias[l]
                for (d in 0 until features) z += output[l * features + d] * h[d]
                g[l] = z
            }
            val max = g.max()
            var sum = 0f
            for (l in 0 until labels) {
                g[l] = exp(g[l] - max)
                sum += g[l]
            }
            for (l in 0 until labels) g[l] = g[l] / sum - if (l == samples[s].label) 1 else 0
            back.fill(0f)
            for (l in 0 until labels) {
                for (d in 0 until GRAM_DIMENSION) back[d] +=
                    output[l * features + DIMENSION + d] * g[l]
            }
            for (l in 0 until labels) {
                for (d in 0 until features) output[l * features + d] -= rate * g[l] * h[d]
                bias[l] -= rate * g[l]
            }
            for (id in ids) {
                for (d in 0 until GRAM_DIMENSION) {
                    grams[id * GRAM_DIMENSION + d] -= rate * back[d] / ids.size
                }
            }
        }
    }
    val gramScale = clip(grams.asList()) / 127
    return CategoryModel.Parts(
        labels = BuiltInCategory.entries.map { it.key },
        threshold = threshold,
        words = embedding.words,
        dimension = DIMENSION,
        wordVectors = embedding.vectors,
        wordScale = embedding.scale,
        buckets = BUCKETS,
        gramDimension = GRAM_DIMENSION,
        shortest = SHORTEST,
        longest = LONGEST,
        gramVectors = ByteArray(grams.size) { quantized(grams[it], gramScale) },
        gramScale = gramScale,
        output = output,
        bias = bias,
    )
}

private fun CategoryModel.top(text: String): Int =
    probabilities(text).let { p -> p.indices.maxBy { p[it] } }

/**
 * Prints how the model does on the held-out terms, and returns the lowest threshold at which
 * [PRECISION] of its guesses are right. A guess on a transfer term is wrong, as none is made there.
 */
private fun report(model: CategoryModel, test: List<Sample>): Float {
    val probabilities = test.map { model.probabilities(it.text) }
    fun correct(i: Int) = model.top(test[i].text) == test[i].label
    println("Held out: %.1f%% right".format(test.indices.count(::correct) * 100.0 / test.size))
    for (language in test.map { it.language }.distinct()) {
        val of = test.indices.filter { test[it].language == language }
        println("  $language: %.1f%%".format(of.count(::correct) * 100.0 / of.size))
    }
    val transfer = model.labels.indexOf(BuiltInCategory.TRANSFER)
    var chosen: Float? = null
    for (step in 1..18) {
        val threshold = step * 0.05f
        var guessed = 0
        var right = 0
        test.indices.forEach { i ->
            val p = probabilities[i]
            val best = p.indices.filter { it != transfer }.maxBy { p[it] }
            if (p[best] >= threshold) {
                guessed++
                if (best == test[i].label) right++
            }
        }
        val precision = right.toDouble() / maxOf(1, guessed)
        println(
            "  at %.2f: %.1f%% guessed, %.1f%% of them right"
                .format(threshold, guessed * 100.0 / test.size, precision * 100)
        )
        if (chosen == null && precision >= PRECISION) chosen = threshold
    }
    println("Threshold: $chosen")
    return chosen ?: 0.5f
}

/** Six times the root mean square, beyond which so few values lie that they are cut off. */
private fun clip(values: List<Float>): Float =
    6 * sqrt(values.sumOf { it.toDouble() * it } / values.size).toFloat()

private fun quantized(value: Float, scale: Float): Byte =
    (value / scale).roundToInt().coerceIn(-127, 127).toByte()

private fun unit(vector: FloatArray): FloatArray {
    val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
    return if (norm == 0f) vector else FloatArray(vector.size) { vector[it] / norm }
}

/** The eigenvalues and, as columns, eigenvectors of the symmetric [matrix], by Jacobi rotations. */
private fun eigen(matrix: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
    val n = matrix.size
    val a = Array(n) { matrix[it].copyOf() }
    val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    val total = a.sumOf { row -> row.sumOf { it * it } }
    for (sweep in 0 until 100) {
        var off = 0.0
        for (p in 0 until n) for (q in 0 until n) if (p != q) off += a[p][q] * a[p][q]
        if (off < 1e-20 * total) break
        for (p in 0 until n - 1) {
            for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until n) {
                    val kp = a[k][p]
                    val kq = a[k][q]
                    a[k][p] = c * kp - s * kq
                    a[k][q] = s * kp + c * kq
                }
                for (k in 0 until n) {
                    val pk = a[p][k]
                    val qk = a[q][k]
                    a[p][k] = c * pk - s * qk
                    a[q][k] = s * pk + c * qk
                }
                for (k in 0 until n) {
                    val kp = v[k][p]
                    val kq = v[k][q]
                    v[k][p] = c * kp - s * kq
                    v[k][q] = s * kp + c * kq
                }
            }
        }
    }
    return DoubleArray(n) { a[it][it] } to v
}
