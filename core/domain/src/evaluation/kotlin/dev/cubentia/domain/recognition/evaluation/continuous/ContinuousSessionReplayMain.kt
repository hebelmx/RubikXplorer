package dev.cubentia.domain.recognition.evaluation.continuous

import java.io.File
import kotlin.system.exitProcess

/**
 * `./gradlew :core:domain:replayContinuousSession [--args='[root] [--session <dir>] [--report <file>] [--refresh-golden]']`:
 * state-replays every complete session under `Fixtures/continuous` (or one `--session`), prints the report (and writes it to
 * `--report`) and exits 1 when a snapshot or cue differs. `--refresh-golden` instead rewrites the sessions' snapshots
 * from this revision's mapper; it is the only way a drift is accepted.
 */
fun main(args: Array<String>) {
    var root = File("Fixtures/continuous")
    var session: File? = null
    var report: File? = null
    var refresh = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--session" -> session = File(args[++i])
            "--report" -> report = File(args[++i])
            "--refresh-golden" -> refresh = true
            else -> root = File(arg)
        }
        i++
    }
    val dirs = session?.let { listOf(it) } ?: ContinuousSessionReplay.sessionsUnder(root)
    val reports =
        if (refresh) {
            val revision = headRevision()
            dirs.map { ContinuousSessionReplay.refreshGolden(it, revision) }
        } else {
            dirs.map(ContinuousSessionReplay::replay)
        }
    val text = ContinuousSessionReplay.markdown(reports)
    println(text)
    report?.let {
        it.absoluteFile.parentFile?.mkdirs()
        it.writeText(text)
        println("Wrote ${it.path}")
    }
    if (reports.any { it.diffs.isNotEmpty() }) {
        System.err.println("Replay differs from the recorded snapshots; accept a mapper change only with --refresh-golden.")
        exitProcess(1)
    }
}

/** The full `git rev-parse HEAD`, or `unknown` outside git. */
internal fun headRevision(): String =
    runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val out =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (process.waitFor() == 0 && out.matches(Regex("[0-9a-f]{40}"))) out else "unknown"
    }.getOrDefault("unknown")
