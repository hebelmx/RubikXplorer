package dev.cubentia.domain.recognition.evaluation.continuous

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: replayContinuousSession [root] [--session <dir>] [--report <file>] [--refresh-golden]"

/** The parsed command line of [main]. */
data class ReplayArgs(
    val root: File = File("Fixtures/continuous"),
    val session: File? = null,
    val report: File? = null,
    val refresh: Boolean = false,
)

/** [args] parsed, or null for a flag without its value, an unknown `--flag` or a second root. */
fun parseReplayArgs(args: Array<String>): ReplayArgs? {
    var parsed = ReplayArgs()
    var rootGiven = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--session" || arg == "--report" -> {
                val value = args.getOrNull(++i)?.takeUnless { it.startsWith("--") } ?: return null
                parsed = if (arg == "--session") parsed.copy(session = File(value)) else parsed.copy(report = File(value))
            }

            arg == "--refresh-golden" -> {
                parsed = parsed.copy(refresh = true)
            }

            arg.startsWith("--") || rootGiven -> {
                return null
            }

            else -> {
                rootGiven = true
                parsed = parsed.copy(root = File(arg))
            }
        }
        i++
    }
    return parsed
}

/**
 * `./gradlew :core:domain:replayContinuousSession [--args='[root] [--session <dir>] [--report <file>] [--refresh-golden]']`:
 * state-replays every complete session under `Fixtures/continuous` (or one `--session`), prints the report (and writes it to
 * `--report`) and exits 1 when a snapshot or cue differs or no session was found (2 for a bad command line).
 * `--refresh-golden` instead rewrites the sessions' snapshots from this revision's mapper; it is the only way a drift is
 * accepted, and it refuses a session that is not exactly replayable.
 */
fun main(args: Array<String>) {
    val parsed =
        parseReplayArgs(args) ?: run {
            System.err.println(USAGE)
            exitProcess(2)
        }
    val dirs = parsed.session?.let { listOf(it) } ?: ContinuousSessionReplay.sessionsUnder(parsed.root)
    if (dirs.isEmpty()) {
        System.err.println("No complete continuous session found under ${parsed.root.path}.")
        exitProcess(1)
    }
    val reports =
        try {
            if (parsed.refresh) {
                val revision = headRevision()
                dirs.map { ContinuousSessionReplay.refreshGolden(it, revision) }
            } else {
                dirs.map(ContinuousSessionReplay::replay)
            }
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    val text = ContinuousSessionReplay.markdown(reports)
    println(text)
    parsed.report?.let {
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
