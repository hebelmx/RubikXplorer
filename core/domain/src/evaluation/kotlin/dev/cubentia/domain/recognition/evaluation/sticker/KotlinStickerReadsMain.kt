package dev.cubentia.domain.recognition.evaluation.sticker

import java.io.File

fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: KotlinStickerReadsMain <holdout.json> <Fixtures root> <output json>" }
    val output = File(args[2])
    output.parentFile.mkdirs()
    output.writeText(KotlinStickerReads.export(File(args[0]), File(args[1])))
    println("Wrote ${output.path}")
}
