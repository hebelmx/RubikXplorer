package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab

data class ScopeRow(
    val scope: String,
    val metrics: Metrics,
    /** Groups in this scope the variant did not score, with the reason. */
    val unscored: List<GroupNote>,
)

data class BadWhiteRow(
    val variant: Variant,
    val recorded: Metrics,
    val badWhite: Metrics,
)

data class SweepRow(
    val t: Double,
    val kCal: Metrics,
    val oCal: Metrics,
)

/** One A2 group's WHITE-center face: the center as each pipeline measures it, against the group's plain white cells. */
data class LogoRow(
    val group: String,
    val knownLogo: Boolean,
    val json: Lab,
    val jsonDeltaE: Double?,
    val oMean: Lab?,
    val oMeanDeltaE: Double?,
    val oMode: Lab?,
    val oModeDeltaE: Double?,
    val modeShare: Double?,
    val readings: List<Pair<Variant, ScoredSticker?>>,
)

data class CenterShare(
    val group: String,
    val face: Face,
    val color: Color,
    val share: Double,
    val knownLogo: Boolean,
) {
    val plain: Boolean get() = share >= LabHistogram.PLAIN_SHARE
}

data class Oq7Report(
    val groups: List<GroupObservation>,
    val runs: List<VariantRun>,
    val loco: LeaveOneCubeOut,
    val scopes: Map<Variant, List<ScopeRow>>,
    val badWhite: List<BadWhiteRow>,
    val sweep: List<SweepRow>,
    val logo: List<LogoRow>,
    val centers: List<CenterShare>,
    val sizes: List<AarSize>,
) {
    fun run(variant: Variant): VariantRun = runs.single { it.variant == variant }

    companion object {
        /** Cubes whose WHITE center carries a logo (session A/A2 notes). */
        val LOGO_CUBES = setOf("stka", "stla")

        fun build(
            groups: List<GroupObservation>,
            sizes: List<AarSize>,
        ): Oq7Report {
            val (mLoco, loco) = RecognitionApproaches.mLoco(groups)
            val runs =
                listOf(
                    RecognitionApproaches.kCal(groups),
                    RecognitionApproaches.kSelf(groups),
                    RecognitionApproaches.oCal(groups),
                    RecognitionApproaches.oSelf(groups),
                    mLoco,
                )
            val badWhite =
                listOf(
                    BadWhiteRow(
                        Variant.K_CAL,
                        Metrics.of(runs[0].stickers),
                        Metrics.of(
                            RecognitionApproaches
                                .kCal(groups) {
                                    Calibrations.withWhite(it)
                                }.stickers,
                        ),
                    ),
                    BadWhiteRow(
                        Variant.O_CAL,
                        Metrics.of(runs[2].stickers),
                        Metrics.of(
                            RecognitionApproaches
                                .oCal(groups) {
                                    Calibrations.withWhite(it)
                                }.stickers,
                        ),
                    ),
                )
            val sweep =
                Calibrations.SWEEP.map { t ->
                    val transform = { centers: Map<Color, Lab> ->
                        Calibrations.withWhite(centers, Calibrations.blend(centers.getValue(Color.WHITE), t))
                    }
                    SweepRow(
                        t,
                        Metrics.of(RecognitionApproaches.kCal(groups, transform).stickers),
                        Metrics.of(RecognitionApproaches.oCal(groups, transform).stickers),
                    )
                }
            return Oq7Report(
                groups = groups,
                runs = runs,
                loco = loco,
                scopes = runs.associate { it.variant to scopes(groups, it) },
                badWhite = badWhite,
                sweep = sweep,
                logo = groups.filter { it.session == Session.A2 }.mapNotNull { logoRow(it, runs) },
                centers =
                    groups.filter { it.hasFrames }.flatMap { group ->
                        group.faces.map { face ->
                            CenterShare(
                                group.name,
                                face.face,
                                face.centerColor,
                                requireNotNull(face.cells)[FaceSample.CENTER_INDEX].modeShare,
                                isKnownLogo(group, face.centerColor),
                            )
                        }
                    },
                sizes = sizes,
            )
        }

        fun isKnownLogo(
            group: GroupObservation,
            centerColor: Color,
        ) = group.cube in LOGO_CUBES && centerColor == Color.WHITE

        fun scopes(
            groups: List<GroupObservation>,
            run: VariantRun,
        ): List<ScopeRow> {
            fun row(
                scope: String,
                members: List<GroupObservation>,
            ): ScopeRow {
                val names = members.map { it.name }.toSet()
                return ScopeRow(
                    scope,
                    Metrics.of(run.stickers.filter { it.group.name in names }),
                    run.notes.filter { it.group in names },
                )
            }
            return buildList {
                Session.entries.forEach { session -> add(row(session.label, groups.filter { it.session == session })) }
                groups.map { it.device }.distinct().sorted().forEach { device ->
                    add(
                        row(
                            "Device: $device",
                            groups.filter {
                                it.device ==
                                    device
                            },
                        ),
                    )
                }
                add(row("Total", groups))
            }
        }

        private fun logoRow(
            group: GroupObservation,
            runs: List<VariantRun>,
        ): LogoRow? {
            val face = group.faces.firstOrNull { it.centerColor == Color.WHITE } ?: return null
            val plainWhite =
                group.faces.flatMap { candidate ->
                    candidate.truth.indices.filter { it != FaceSample.CENTER_INDEX && candidate.truth[it] == Color.WHITE }.map {
                        candidate to
                            it
                    }
                }
            val json = face.samples[FaceSample.CENTER_INDEX]
            val jsonReference =
                plainWhite
                    .map { (candidate, index) ->
                        candidate.samples[index]
                    }.takeIf { it.isNotEmpty() }
                    ?.let(LabHistogram::mean)
            val cell = face.cells?.get(FaceSample.CENTER_INDEX)

            fun frameReference(feature: (CellFeature) -> Lab) =
                if (group.hasFrames) {
                    plainWhite
                        .map { (candidate, index) -> feature(requireNotNull(candidate.cells)[index]) }
                        .takeIf { it.isNotEmpty() }
                        ?.let(LabHistogram::mean)
                } else {
                    null
                }
            val meanReference = frameReference { it.mean }
            val modeReference = frameReference { it.mode }

            fun distance(
                value: Lab?,
                reference: Lab?,
            ) = if (value == null || reference == null) null else NearestCenter.deltaE(value, reference)
            return LogoRow(
                group = group.name,
                knownLogo = isKnownLogo(group, Color.WHITE),
                json = json,
                jsonDeltaE = distance(json, jsonReference),
                oMean = cell?.mean,
                oMeanDeltaE = distance(cell?.mean, meanReference),
                oMode = cell?.mode,
                oModeDeltaE = distance(cell?.mode, modeReference),
                modeShare = cell?.modeShare,
                readings = runs.map { it.variant to it.reading(group.name, face.face, FaceSample.CENTER_INDEX) },
            )
        }
    }
}
