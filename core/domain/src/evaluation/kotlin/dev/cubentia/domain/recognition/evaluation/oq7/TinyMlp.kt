package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.Lab
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The ML arm: a tiny MLP (L/100, a/128, b/128 → 12 tanh → 6 softmax over [Color.entries]) with no calibration input,
 * trained in plain Kotlin by full-batch gradient descent from a fixed seed. Inference is plain Kotlin over the weights.
 */
class TinyMlp private constructor(
    private val w1: DoubleArray,
    private val b1: DoubleArray,
    private val w2: DoubleArray,
    private val b2: DoubleArray,
) {
    fun probabilities(lab: Lab): DoubleArray = forward(inputs(lab)).second

    fun predict(lab: Lab): Prediction {
        val probabilities = probabilities(lab)
        val best = probabilities.indices.maxBy { probabilities[it] }
        val confidence = if (probabilities[best] >= CONFIDENT_PROBABILITY) Confidence.CONFIDENT else Confidence.UNCERTAIN
        return Prediction(COLORS[best], confidence)
    }

    private fun forward(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val hidden = DoubleArray(HIDDEN) { h -> tanh(b1[h] + (0 until INPUTS).sumOf { i -> w1[h * INPUTS + i] * x[i] }) }
        val logits = DoubleArray(OUTPUTS) { o -> b2[o] + (0 until HIDDEN).sumOf { h -> w2[o * HIDDEN + h] * hidden[h] } }
        val top = logits.max()
        val exps = logits.map { exp(it - top) }
        val sum = exps.sum()
        return hidden to DoubleArray(OUTPUTS) { exps[it] / sum }
    }

    companion object {
        const val INPUTS = 3
        const val HIDDEN = 12
        val COLORS = Color.entries
        val OUTPUTS = COLORS.size
        const val SEED = 13L
        const val EPOCHS = 3000
        const val LEARNING_RATE = 0.5
        const val CONFIDENT_PROBABILITY = 0.90
        val PARAMETERS = HIDDEN * INPUTS + HIDDEN + OUTPUTS * HIDDEN + OUTPUTS

        /** Bytes of the trained weights stored as float32. */
        val WEIGHT_BYTES = PARAMETERS * Float.SIZE_BYTES

        fun inputs(lab: Lab) = doubleArrayOf(lab.l / 100.0, lab.a / 128.0, lab.b / 128.0)

        fun train(
            examples: List<Pair<Lab, Color>>,
            seed: Long = SEED,
        ): TinyMlp {
            require(examples.isNotEmpty()) { "No training examples" }
            val random = java.util.Random(seed)
            val w1 = DoubleArray(HIDDEN * INPUTS) { random.nextGaussian() * sqrt(1.0 / INPUTS) }
            val b1 = DoubleArray(HIDDEN)
            val w2 = DoubleArray(OUTPUTS * HIDDEN) { random.nextGaussian() * sqrt(1.0 / HIDDEN) }
            val b2 = DoubleArray(OUTPUTS)
            val model = TinyMlp(w1, b1, w2, b2)
            val xs = examples.map { inputs(it.first) }
            val ys = examples.map { COLORS.indexOf(it.second) }
            val scale = 1.0 / examples.size
            repeat(EPOCHS) {
                val gw1 = DoubleArray(w1.size)
                val gb1 = DoubleArray(b1.size)
                val gw2 = DoubleArray(w2.size)
                val gb2 = DoubleArray(b2.size)
                xs.indices.forEach { n ->
                    val x = xs[n]
                    val (hidden, probabilities) = model.forward(x)
                    val dLogits = DoubleArray(OUTPUTS) { o -> probabilities[o] - if (o == ys[n]) 1.0 else 0.0 }
                    for (o in 0 until OUTPUTS) {
                        gb2[o] += dLogits[o]
                        for (h in 0 until HIDDEN) gw2[o * HIDDEN + h] += dLogits[o] * hidden[h]
                    }
                    for (h in 0 until HIDDEN) {
                        val dHidden = (0 until OUTPUTS).sumOf { o -> dLogits[o] * w2[o * HIDDEN + h] } * (1 - hidden[h] * hidden[h])
                        gb1[h] += dHidden
                        for (i in 0 until INPUTS) gw1[h * INPUTS + i] += dHidden * x[i]
                    }
                }
                for (k in w1.indices) w1[k] -= LEARNING_RATE * scale * gw1[k]
                for (k in b1.indices) b1[k] -= LEARNING_RATE * scale * gb1[k]
                for (k in w2.indices) w2[k] -= LEARNING_RATE * scale * gw2[k]
                for (k in b2.indices) b2[k] -= LEARNING_RATE * scale * gb2[k]
            }
            return model
        }
    }
}

data class LabeledLab(
    val cube: String,
    val lab: Lab,
    val truth: Color,
)

data class Fold(
    val heldOut: String,
    val trainingCubes: List<String>,
    val trainingSize: Int,
    val testSize: Int,
)

data class LeaveOneCubeOut(
    val folds: List<Fold>,
    /** Aligned with the input examples; each comes from the fold that held its cube out. */
    val predictions: List<Prediction>,
)

object LeaveOneCubeOutTraining {
    /** One fold per cube id (sorted): train on every other cube, predict the held-out cube's stickers. */
    fun run(examples: List<LabeledLab>): LeaveOneCubeOut {
        val cubes = examples.map { it.cube }.distinct().sorted()
        val predictions = arrayOfNulls<Prediction>(examples.size)
        val folds =
            cubes.map { heldOut ->
                val training = examples.filter { it.cube != heldOut }
                val model = TinyMlp.train(training.map { it.lab to it.truth })
                examples.forEachIndexed { index, example ->
                    if (example.cube == heldOut) predictions[index] = model.predict(example.lab)
                }
                Fold(heldOut, cubes - heldOut, training.size, examples.count { it.cube == heldOut })
            }
        return LeaveOneCubeOut(folds, predictions.map { requireNotNull(it) })
    }
}
