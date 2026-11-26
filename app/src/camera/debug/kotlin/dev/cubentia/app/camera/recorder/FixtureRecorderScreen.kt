package dev.cubentia.app.camera.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.cubentia.app.camera.CornerOutline
import dev.cubentia.app.camera.CornerPoint
import dev.cubentia.app.camera.FrameReader
import dev.cubentia.app.camera.TorchButton
import dev.cubentia.app.camera.TorchControl
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.ClassificationResult
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.CornerFixturePaths
import dev.cubentia.domain.recognition.CornerFixtureRecord
import dev.cubentia.domain.recognition.CornerKeypointFixture
import dev.cubentia.domain.recognition.CornerLabel
import dev.cubentia.domain.recognition.CornerSettle
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LightDirection
import dev.cubentia.domain.recognition.LiveFixturePaths
import dev.cubentia.domain.recognition.NearestCenterClassifier
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.TruthSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import kotlin.math.hypot
import kotlin.math.pow
import androidx.compose.ui.graphics.Color as UiColor

/** Editor palette order (2×3: white yellow / red orange / green blue), each with its glyph letter. */
private val PALETTE = listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.GREEN, Color.BLUE)

private const val PREFILL_REFRESH_MS = 300L
private const val REMETER_NOTE_MS = 2000L

/** Story 10.20: how often a corner Capture press polls the 10.22 settle signal, and the slack past its cap. */
private const val SETTLE_POLL_MS = 50L
private const val SETTLE_WAIT_SLACK_MS = 500L

/** A sampled calibration swatch shown before the operator saves or retakes it. */
private data class SwatchPreview(
    val color: Color,
    val swatch: Lab,
    val warnings: List<SwatchWarning>,
)

private enum class Confirm { RESET_LABELS, RECALIBRATE }

/** Story 10.28: a corner group the count check refused, its folder, and the faces whose over-coloured pre-fills are ringed. */
private data class GroupRefusal(
    val check: ColorCountCheck,
    val directory: File,
    val faces: List<RefusalFace>,
)

/** What a corner-mode capture records (Story 10.19). */
private enum class CornerCapture { CORNER, KEYPOINTS_ONLY, NEGATIVE }

/**
 * A frozen corner capture whose seven corners the operator places on the model-input crop (Story 10.19): [points] are
 * the manual labels, [detector] what the detector found (diagnostics only); [samples] are the color samples, null when
 * the detector missed or only keypoints are recorded; [miss] is why a corner capture has no samples (GH-329).
 */
private data class Placement(
    val shot: CapturedFrame,
    val kind: CornerCapture,
    val samples: List<FaceSample>?,
    val detector: List<List<Double>>?,
    val points: List<List<Double>>,
    val miss: CornerMiss?,
)

/**
 * One-screen recorder (Story 10.12 UX pass): viewfinder with grid and 6-face progress, then the face net beside the
 * palette, a status line and one primary action. Rare controls live in the header row, each behind a confirm dialog.
 */
@Composable
internal fun FixtureRecorderScreen(camera: RecorderCamera) {
    // Story 10.34: Continuous mode is offered only when the continuous engine is composed (AD-18).
    val continuous = camera.continuous
    var continuousMode by remember { mutableStateOf(false) }
    if (continuousMode && continuous != null) {
        ContinuousRecorderScreen(camera, continuous, onLeave = { continuousMode = false })
    } else {
        FaceCornerRecorderScreen(camera, onContinuous = continuous?.let { { continuousMode = true } })
    }
}

/** The per-face and corner recorder (Stories 10.12–10.28), unchanged by Story 10.34 apart from the mode entry. */
@Composable
private fun FaceCornerRecorderScreen(
    camera: RecorderCamera,
    onContinuous: (() -> Unit)?,
) {
    val context = LocalContext.current
    val initialPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(initialPermission) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    val scope = rememberCoroutineScope()

    var cube by remember { mutableStateOf("") }
    var light by remember { mutableStateOf("") }
    var editingMeta by remember { mutableStateOf(true) }
    val centers = remember { mutableStateMapOf<Color, Lab>() }
    var calibrationColor by remember { mutableStateOf(Color.WHITE) }
    var preview by remember { mutableStateOf<SwatchPreview?>(null) }
    var selectedFace by remember { mutableStateOf(Face.U) }
    val capturedFaces = remember { mutableStateListOf<Face>() }
    var entry by remember { mutableStateOf(TruthEntry(selected = null)) }
    var qualityOverride by remember { mutableStateOf<CaptureQuality?>(null) }
    var cornerMode by remember { mutableStateOf(false) }
    var cornerHold by remember { mutableStateOf(1) }

    // GH-318: the hold whose keypoints-only save missed its colors; its primary button reads Retake until the next capture.
    var missedHold by remember { mutableStateOf<Int?>(null) }
    var cornerShot by remember { mutableStateOf<CapturedFrame?>(null) }
    var cornerSamples by remember { mutableStateOf<List<FaceSample>>(emptyList()) }
    var lightDirection by remember { mutableStateOf<LightDirection?>(null) }

    // Story 10.19: the torch the corner session records with (Quick Scan's default, on), only on a camera with a flash.
    var torchOn by remember { mutableStateOf(true) }
    val hasFlash by camera.hasFlash
    val torch = recordedTorch(hasFlash, torchOn)
    var placement by remember { mutableStateOf<Placement?>(null) }

    // The confirmed placement of the capture now being labeled (written with its color record).
    var cornerKeypoints by remember { mutableStateOf<Placement?>(null) }

    var cornerGroup by remember { mutableStateOf(CornerRecordingGroup()) }
    val cornerLabels = remember { mutableStateListOf<Pair<List<Color>, List<TruthSource>>>() }
    val cornerReviewed = remember { mutableStateListOf<List<Boolean>>() }

    // Story 10.28: the latest confirmed labels of each written hold of the current group, the refusal and the override.
    val groupHolds = remember { mutableStateMapOf<Int, CornerHoldLabels>() }
    var refusal by remember { mutableStateOf<GroupRefusal?>(null) }
    var overriding by remember { mutableStateOf(false) }
    var overrideText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // The face whose saved truth the net is showing after a capture; Capture and prefill wait until the hold ends.
    var holdFace by remember { mutableStateOf<Face?>(null) }
    var calibrationHold by remember { mutableStateOf(false) }
    var latestUsable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Set the cube and light, then calibrate the six colors.") }
    var coaching by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var choosingOverride by remember { mutableStateOf(false) }
    val calibrated = centers.size == Color.entries.size
    val metaReady =
        cube.any { it.isLetterOrDigit() && it.code < 128 } &&
            light.any { it.isLetterOrDigit() && it.code < 128 } &&
            (!cornerMode || lightDirection != null)
    val device = DeviceInfo(Build.MODEL, Build.MANUFACTURER, Build.VERSION.RELEASE, Build.DISPLAY, Build.VERSION.SDK_INT)

    LaunchedEffect(cornerMode, calibrated) { camera.cornerMode(cornerMode && calibrated) }

    // Story 10.20: the torch follows the operator's choice from the moment corner mode is set, so the six colors are
    // calibrated under the torch state the corners are recorded with.
    LaunchedEffect(cornerMode, torchOn, hasFlash) { camera.torch(recorderTorchOn(cornerMode, hasFlash, torchOn)) }
    val calibrationNow = if (calibrated) CalibrationCenters(centers.toMap()) else null
    LaunchedEffect(calibrationNow) { camera.calibration(calibrationNow) }

    // Quick Scan's own en.json strings for the shared torch control (no recorder copy of its labels).
    val strings by produceState<ContentStrings?>(null) {
        value =
            withContext(Dispatchers.IO) {
                (ContentLoader.load(ContentSource.SHIPPED) as? ContentLoadResult.Loaded)?.bundle?.strings?.let(::ContentStrings)
            }
    }

    fun resetCorner() {
        missedHold = null
        cornerShot = null
        cornerSamples = emptyList()
        cornerLabels.clear()
        cornerReviewed.clear()
        entry = TruthEntry(selected = null)
        placement = null
        cornerKeypoints = null
    }

    /** Story 10.28: forgets the current group's holds and any refusal (a new group starts with the next hold). */
    fun clearGroupCheck() {
        groupHolds.clear()
        refusal = null
        overriding = false
    }

    /**
     * A new capture condition (Story 10.19): the next corners start a new group. With [recalibrate] (a new cube, light
     * or, since Story 10.20, torch state) the six calibrated colors are cleared too, so they are sampled again under it.
     */
    fun newCondition(recalibrate: Boolean) {
        resetCorner()
        clearGroupCheck()
        cornerHold = 1
        cornerGroup = CornerRecordingGroup()
        if (recalibrate) {
            if (centers.isNotEmpty()) status = "Cube or light changed: calibrate all six again."
            centers.clear()
            preview = null
            calibrationColor = Color.WHITE
            entry = TruthEntry(selected = null)
        }
    }

    /** The corner view's torch control (Story 10.20): the same path as the Settings dialog's torch checkbox. */
    fun toggleTorch() {
        if (saving) return
        val next = !torchOn
        val recalibrate = recalibrates(cube, cube, light, light, torchOn, next, cornerMode, cornerMode, hasFlash)
        torchOn = next
        newCondition(recalibrate)
    }

    /** Prepares the target folder and its manifest. The group becomes current after the capture is written. */
    suspend fun groupDirectory(save: CornerGroupSave): File {
        val group = cornerGroup
        val base = CornerFixturePaths.directoryName(device.model, cube.trim(), light.trim())
        val manifest = groupManifest(device, cube, light, checkNotNull(lightDirection), torch, CalibrationCenters(centers.toMap()))
        return withContext(Dispatchers.IO) {
            val root = checkNotNull(context.getExternalFilesDir(null)) { "External files directory unavailable" }
            val parent = File(root, "Fixtures/corner")
            val name =
                group.targetName(save, base) { candidate ->
                    File(parent, candidate).let { it.exists() && it.list()?.isEmpty() != true }
                }
            val directory = File(parent, name)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
            ensureGroupManifest(directory, manifest)
            directory
        }
    }

    /** IO: writes one keypoint fixture (the model-input crop and its labels) into the given group; returns its number. */
    fun writeKeypoints(
        directory: File,
        shot: CapturedFrame,
        label: CornerLabel,
        points: List<List<Double>>?,
        detector: List<List<Double>>?,
    ): Int {
        val folder = File(directory, CornerFixturePaths.KEYPOINTS_DIR)
        check(folder.isDirectory || folder.mkdirs()) { "Cannot create $folder" }
        val index = nextKeypointIndex(folder.list().orEmpty().toList())
        val fixture =
            CornerKeypointFixture(
                frame = CornerFixturePaths.keypointFrameName(index),
                label = label,
                keypoints = points,
                detectorKeypoints = detector,
                device = device,
                cube = cube.trim(),
                light = light.trim(),
                lightDirection = checkNotNull(lightDirection),
                capturedAt = shot.capturedAt.toString(),
                torch = torch,
            )
        File(folder, fixture.frame).writeBytes(checkNotNull(shot.modelInput) { "No model-input crop was encoded" })
        File(folder, CornerFixturePaths.keypointFileName(index)).writeText(CornerFixtureJson.encodeKeypoints(fixture))
        return index
    }

    suspend fun saveSupplemental(
        shot: CapturedFrame,
        label: CornerLabel,
        points: List<List<Double>>?,
        detector: List<List<Double>>?,
    ): Int {
        val directory = groupDirectory(CornerGroupSave.SUPPLEMENTAL)
        val index = withContext(Dispatchers.IO) { writeKeypoints(directory, shot, label, points, detector) }
        cornerGroup = cornerGroup.saved(directory.name, CornerGroupSave.SUPPLEMENTAL)
        return index
    }

    /**
     * Corner mode (Story 10.19): a corner with colors, a keypoint-only positive, or a no-cube negative. Positives freeze for
     * manual corner placement, even when the detector missed (colors are then not recorded); a negative saves at once.
     */
    fun captureCorner(kind: CornerCapture) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                // Story 10.20: a press while the 10.22 metering settles waits for it (or its cap), as Quick Scan queues one.
                if (camera.settled() == false) {
                    status = "Waiting for exposure to settle…"
                    withTimeoutOrNull(CornerSettle.CAP_MS + SETTLE_WAIT_SLACK_MS) {
                        while (camera.settled() == false) delay(SETTLE_POLL_MS)
                    }
                }
                val shot = camera.capture()
                val crop = shot?.crop
                if (shot?.modelInput == null || crop == null) {
                    status = "No camera frame arrived; try again."
                    return@launch
                }
                if (kind == CornerCapture.NEGATIVE) {
                    val index =
                        saveSupplemental(shot, CornerLabel.NEGATIVE, null, detectorKeypoints(shot.corner?.frameQuads.orEmpty(), crop))
                    status = "Saved negative %04d.".format(index)
                    return@launch
                }
                val samples =
                    if (kind == CornerCapture.CORNER &&
                        shot.jpeg != null
                    ) {
                        shot.corner?.let { cornerSamples(cornerHold, it) }
                    } else {
                        null
                    }
                val detector = detectorKeypoints(shot.corner?.frameQuads.orEmpty(), crop)
                val miss =
                    if (kind == CornerCapture.CORNER && samples == null) cornerMiss(cornerHold, shot.corner) ?: CornerMiss.NO_CUBE else null
                if (kind == CornerCapture.CORNER) missedHold = null
                placement = Placement(shot, kind, samples, detector, initialKeypoints(), miss)
                status = miss?.let(::cornerMissStatus) ?: "Drag the 7 corners onto the cube, then Use corners."
            } catch (failure: Exception) {
                status = "Save failed: ${failure.message}"
            } finally {
                saving = false
            }
        }
    }

    /** The operator placed the corners: label colors next, or save the keypoint-only positive now. */
    fun usePlacement() {
        if (saving) return
        val placed = placement ?: return
        val samples = placed.samples
        placement = null
        if (samples != null) {
            cornerShot = placed.shot
            cornerSamples = samples
            cornerKeypoints = placed
            entry = TruthEntry().withPrefill(prefill(samples.first().stickers, CalibrationCenters(centers.toMap())))
            status = "Check and confirm all nine stickers on each face."
            return
        }
        saving = true
        val hold = cornerHold
        scope.launch {
            try {
                val index = saveSupplemental(placed.shot, CornerLabel.POSITIVE, placed.points, placed.detector)
                status = keypointsOnlySaved(index, hold, placed.miss)
                if (placed.miss != null) missedHold = hold
            } catch (failure: Exception) {
                status = "Save failed: ${failure.message}"
            } finally {
                saving = false
            }
        }
    }

    fun confirmCornerFace() {
        if (saving) return
        val shot = cornerShot ?: return
        val labels = entry.confirmed() ?: return
        // Story 10.28: a side face is confirmed only once each of its pre-filled stickers was tapped or colour-picked.
        if (cornerFaceLocks(cornerLabels.size, entry).isNotEmpty()) return
        val face = cornerSamples[cornerLabels.size].face
        if (labels.first[FaceSample.CENTER_INDEX] != Color.home(face)) {
            status = "Label the ${colorLabel(Color.home(face))} center before continuing."
            return
        }
        val calibration = CalibrationCenters(centers.toMap())
        val nextLabels = cornerLabels + listOf(labels)
        val nextReviewed = cornerReviewed + listOf(entry.reviewed)
        val next = cornerSamples.getOrNull(nextLabels.size)
        if (next != null) {
            cornerLabels.add(labels)
            cornerReviewed.add(entry.reviewed)
            entry = TruthEntry().withPrefill(prefill(next.stickers, calibration))
            return
        }
        val placed = cornerKeypoints
        // The torch at this capture: the state the camera applied, on a camera with a flash unit only.
        val holdTorch = holdTorch(torch, shot.torch)
        val record =
            CornerFixtureRecord(
                device = device,
                cube = cube.trim(),
                light = light.trim(),
                capturedAt = shot.capturedAt.toString(),
                calibration = calibration,
                hold = cornerHold,
                labeledSamples = cornerSamples.zip(nextLabels).map { (sample, label) -> LabeledFaceSample(sample, label.first) },
                truthSources = nextLabels.map { it.second },
                lightDirection = lightDirection,
                keypoints = placed?.points,
                detectorKeypoints = placed?.detector,
                torch = holdTorch,
                diagnostics = holdDiagnostics(shot.corner?.faces.orEmpty(), calibration, holdTorch, shot.settle, shot.evIndex),
                reviewed = nextReviewed,
            )
        val holdLabels =
            CornerHoldLabels(cornerSamples.map { it.face }, nextLabels.map { it.first }, nextLabels.map { it.second }, nextReviewed)
        saving = true
        scope.launch {
            try {
                val directory = groupDirectory(CornerGroupSave.HOLD)
                withContext(Dispatchers.IO) { writeCornerHold(directory, record, checkNotNull(shot.jpeg)) }
                // A retry after a companion write fails must use this folder, which already contains the hold.
                cornerGroup = cornerGroup.saved(directory.name, CornerGroupSave.HOLD)
                val manifestFailure =
                    withContext(Dispatchers.IO) {
                        // Record the saved hold even if the paired keypoints fail to write.
                        val failure = runCatching { recordHold(directory, record.hold, holdTorch) }.exceptionOrNull()
                        if (placed != null) writeKeypoints(directory, placed.shot, CornerLabel.POSITIVE, placed.points, placed.detector)
                        failure
                    }
                // Story 10.28: once both holds are written, count both holds' latest labels and record the outcome.
                groupHolds[record.hold] = holdLabels
                val holds = groupHolds.toMap()
                val check = colorCount(holds)
                val checkFailure =
                    if (check != null && manifestFailure == null) {
                        withContext(Dispatchers.IO) { runCatching { recordCountCheck(directory, check, null) }.exceptionOrNull() }
                    } else {
                        null
                    }
                val saved = "Saved corner ${record.hold} in ${directory.name}."
                status =
                    when {
                        manifestFailure != null -> "$saved group.json not updated: ${manifestFailure.message}"
                        checkFailure != null -> "$saved Count check not written: ${checkFailure.message}"
                        check == null -> saved
                        check.passed -> "Saved corner group ${directory.name} · 9 of each colour."
                        else -> "Wrote corner ${record.hold} in ${directory.name}; the group is not saved."
                    }
                cornerHold = nextCornerHold(holds.keys)
                resetCorner()
                if (check != null) {
                    if (check.passed) {
                        clearGroupCheck()
                    } else {
                        refusal = GroupRefusal(check, directory, overCountedPrefills(holds, check.over.keys))
                    }
                }
            } catch (failure: Exception) {
                status = "Save failed: ${failure.message}"
            } finally {
                saving = false
            }
        }
    }

    /** Story 10.28: after a refusal, capture hold [hold] again into the same group folder; the check reruns once written. */
    fun retakeHold(hold: Int) {
        if (saving) return
        refusal = null
        cornerHold = hold
        resetCorner()
        status = "Retake corner $hold: capture it again."
    }

    /** Record both corners again: the next hold starts a new group folder. */
    fun recordBothAgain() {
        if (saving) return
        cornerHold = 1
        cornerGroup = cornerGroup.restartHolds()
        resetCorner()
        clearGroupCheck()
    }

    /** Story 10.28: saves the refused group with the operator's non-blank [reason] in its manifest. */
    fun saveOverride(reason: String) {
        val current = refusal ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { recordCountCheck(current.directory, current.check, reason) }
                status = "Saved corner group ${current.directory.name} with an override."
                clearGroupCheck()
            } catch (failure: Exception) {
                // Close the dialog so the failure in the status line is visible; Override… stays available to retry.
                overriding = false
                status = "Save failed: ${failure.message}"
            } finally {
                saving = false
            }
        }
    }

    fun resetEntry() {
        entry = TruthEntry(selected = null)
    }

    /** Prefills from the latest acceptable frame now (no-op while saving, holding, uncalibrated or after an entry). */
    fun refreshPrefill() {
        if (cornerMode) return
        val frame = camera.latest()
        latestUsable = frame != null && frame.quality == CaptureQuality.ACCEPTABLE && frame.lab.size == 9
        if (frame != null && latestUsable && centers.size == Color.entries.size && !saving && holdFace == null) {
            entry = entry.withPrefill(prefill(frame.lab, CalibrationCenters(centers.toMap())))
        }
    }

    fun selectFace(face: Face) {
        selectedFace = face
        holdFace = null
        resetEntry()
        refreshPrefill()
    }

    // Faces already saved for this device, cube and light survive a restart: read them back from the export directory.
    LaunchedEffect(cube.trim(), light.trim()) {
        val saved =
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = context.getExternalFilesDir(null) ?: return@runCatching emptyList()
                    val directory = File(root, "Fixtures/live/${LiveFixturePaths.directoryName(Build.MODEL, cube.trim(), light.trim())}")
                    Face.entries.filter { File(directory, LiveFixturePaths.fileName(it)).isFile }
                }.getOrDefault(emptyList())
            }
        capturedFaces.clear()
        capturedFaces.addAll(saved)
        selectFace(Face.entries.firstOrNull { it !in saved } ?: Face.U)
    }
    // Re-meter on the grid on every face selection and calibration-color change; realignment is handled by the analyzer.
    LaunchedEffect(selectedFace, calibrationColor, calibrated, cornerHold, cornerMode, torchOn) {
        // Corner mode meters like Quick Scan (Story 10.19): on the detected outline, AWB on hold 1 only.
        if (cornerMode && calibrated) camera.remeterCorner(includeAwb = cornerHold == 1) else camera.remeter()
    }
    val liveQuads by camera.detectedQuads
    val autoRemeters by camera.autoRemeters
    var remeterNote by remember { mutableStateOf<String?>(null) }
    // Shown beside the status, so it never hides a save result.
    LaunchedEffect(autoRemeters) {
        if (autoRemeters > 0) {
            remeterNote = "re-metered"
            delay(REMETER_NOTE_MS)
            remeterNote = null
        }
    }
    // Prefill from the latest acceptable frame until the operator enters the first sticker of this face.
    // Face changes and the end of a capture hold also refresh at once (selectFace), not only on this tick.
    LaunchedEffect(selectedFace, calibrated, cornerMode) {
        while (true) {
            coaching = camera.latest()?.detail
            refreshPrefill()
            delay(PREFILL_REFRESH_MS)
        }
    }

    val expectedCenter = FACE_CENTER.getValue(selectedFace)
    val holding = holdFace != null
    // Why Capture is disabled, worded for the operator; empty when it is enabled.
    val captureLocks = captureLocks(saving, holding, metaReady, centers.size, entry, selectedFace)
    val sampleLocks =
        buildList {
            if (!metaReady) add("cube and light")
            if (!hasPermission) add("camera permission")
            if (calibrationHold) add("hold (swatch just saved)")
        }
    val stepLine =
        when {
            !metaReady -> {
                "Step 1/3 · Enter cube and light"
            }

            !calibrated -> {
                "Step 2/3 · Calibrate ${colorLabel(calibrationColor)} (${centers.size}/6)"
            }

            cornerMode && refusal != null -> {
                "Step 3/3 · Group not saved: colour counts are off"
            }

            cornerMode && cornerHold > 2 -> {
                "Step 3/3 · Both corners saved"
            }

            cornerMode -> {
                "Step 3/3 · Corner $cornerHold / 2" +
                    (cornerSamples.getOrNull(cornerLabels.size)?.let { " · Label ${colorLabel(Color.home(it.face))}" } ?: "")
            }

            else -> {
                "Step 3/3 · Face $selectedFace · ${colorLabel(expectedCenter)} center (${capturedFaces.size}/6 saved)"
            }
        }
    // Story 10.28: a side face of a hold stays locked until each pre-filled sticker was tapped or colour-picked.
    val cornerLocks = if (cornerMode && cornerShot != null) cornerFaceLocks(cornerLabels.size, entry) else emptyList()
    val lockLine =
        when {
            !calibrated && preview == null && sampleLocks.isNotEmpty() -> "Sample locked: ${sampleLocks.joinToString("; ")}"
            calibrated && !cornerMode && captureLocks.isNotEmpty() -> "Capture locked: ${captureLocks.joinToString("; ")}"
            calibrated && cornerLocks.isNotEmpty() -> cornerLocks.joinToString("; ")
            else -> null
        }

    fun sample() {
        val frame = camera.latest()
        status =
            when {
                frame == null || frame.ring.isEmpty() -> {
                    "Waiting for a camera frame."
                }

                frame.quality != CaptureQuality.ACCEPTABLE -> {
                    frame.detail ?: "Frame not usable; recapture."
                }

                else -> {
                    val swatch = mean(frame.ring)
                    val warnings = swatchWarnings(calibrationColor, swatch, frame.ring, centers.toMap())
                    preview = SwatchPreview(calibrationColor, swatch, warnings)
                    if (warnings.isEmpty()) {
                        "Check the swatch, then Save."
                    } else {
                        "Use a plain ${colorLabel(
                            calibrationColor,
                        )} sticker in the center cell."
                    }
                }
            }
    }

    fun saveSwatch(value: SwatchPreview) {
        // Story 10.20: a group's manifest carries the calibration it was recorded with, so a changed swatch starts a new one.
        if (cornerGroup.name != null) newCondition(recalibrate = false)
        centers[value.color] = value.swatch
        preview = null
        val next = PALETTE.firstOrNull { it !in centers }
        status = "Saved ${colorLabel(value.color)} (${centers.size}/6)." + if (next == null) " Calibrated: capture the six faces." else ""
        next?.let { calibrationColor = it }
        calibrationHold = true
        scope.launch {
            delay(CALIBRATION_HOLD_MS)
            calibrationHold = false
        }
    }

    fun capture() {
        val face = selectedFace
        val (truth, sources) = entry.confirmed() ?: return
        val override = qualityOverride
        val cubeAtTap = cube
        val lightAtTap = light
        val calibration = CalibrationCenters(centers.toMap())
        saving = true
        scope.launch {
            try {
                val captured = camera.capture()
                if (captured == null) {
                    status = "No camera frame arrived; try again."
                } else {
                    val frame = captured.measurements
                    val sample =
                        FaceSample(
                            face = face,
                            orientationDegrees = frame.orientation,
                            stickers = frame.lab,
                            quality = override ?: frame.quality,
                            qualityDetail = override?.let { "$it marked; recapture this face." } ?: frame.detail,
                        )
                    val record =
                        fixtureRecord(
                            device =
                                DeviceInfo(
                                    Build.MODEL,
                                    Build.MANUFACTURER,
                                    Build.VERSION.RELEASE,
                                    Build.DISPLAY,
                                    Build.VERSION.SDK_INT,
                                ),
                            cube = cubeAtTap,
                            light = lightAtTap,
                            capturedAt = Instant.now(),
                            calibration = calibration,
                            sample = sample,
                            truth = truth,
                            sources = sources,
                            hasFrame = captured.jpeg != null,
                        )
                    withContext(Dispatchers.IO) {
                        val root = checkNotNull(context.getExternalFilesDir(null)) { "External files directory unavailable" }
                        writeFixture(root, record, captured.jpeg)
                    }
                    val prediction =
                        when (val result = NearestCenterClassifier.classify(sample, calibration, truth)) {
                            is ClassificationResult.Unavailable -> "unavailable: ${result.reason}"
                            is ClassificationResult.Classified -> "nearest-center ${result.value.correct}/${result.value.total}"
                        }
                    val frameNote = if (captured.jpeg != null) "+ frame" else "no frame (${captured.frameError})"
                    status = savedSummary(face, sources, override) + " · $frameNote · $prediction"
                    if (face !in capturedFaces) capturedFaces.add(face)
                    qualityOverride = null
                    // Keep showing the saved truth unless the operator already picked another face while it was saving.
                    if (selectedFace == face) holdFace = face
                }
            } catch (failure: Exception) {
                status = "Save failed: ${failure.message}"
            } finally {
                saving = false
            }
            if (holdFace == face) {
                delay(CAPTURE_HOLD_MS)
                // A face the operator picked during the hold wins over the automatic advance (selectFace clears holdFace).
                if (holdFace == face) selectFace(nextFace(capturedFaces, face))
            } else {
                refreshPrefill()
            }
        }
    }

    fun suggestAgain() {
        val frame = camera.latest()
        if (frame == null || frame.quality != CaptureQuality.ACCEPTABLE || frame.lab.size != 9 || !calibrated) {
            status = "No usable frame for Suggest again; hold the cube in the grid."
            return
        }
        entry = entry.suggestAgain(prefill(frame.lab, CalibrationCenters(centers.toMap())))
        status = "Suggested again from the live frame; your entries kept."
    }

    val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
        Viewfinder(
            modifier = modifier,
            hasPermission = hasPermission,
            onAllow = { permission.launch(Manifest.permission.CAMERA) },
            bind = camera::bind,
            calibrated = calibrated && !cornerMode,
            corner = calibrated && cornerMode,
            detectedQuads = if (calibrated && cornerMode) liveQuads else emptyList(),
            placement = placement,
            onMovePoint = { index, du, dv ->
                placement =
                    placement?.let { current ->
                        current.copy(points = current.points.mapIndexed { i, p -> if (i == index) movedKeypoint(p, du, dv) else p })
                    }
            },
            selectedFace = selectedFace,
            capturedFaces = capturedFaces,
            // Story 10.20: Quick Scan's torch control in corner mode on a camera with a flash unit, calibration included.
            torch = strings?.takeIf { cornerMode && hasFlash == true }?.let { TorchControl(torchOn, it, ::toggleTorch) },
            prompt =
                if (calibrated && cornerMode) {
                    val hold = QuickScanPlan.holds.getOrNull(cornerHold - 1)
                    hold?.joinToString(prefix = "Top, left, right: ") { colorLabel(it.centerColor) }.orEmpty()
                } else if (calibrated) {
                    "Face $selectedFace · ${colorLabel(expectedCenter)} center"
                } else {
                    "Calibrate ${colorLabel(calibrationColor)}: plain sticker in the center cell"
                },
            onFace = ::selectFace,
        )
    }
    val entryPanel: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderRow(
                cube = cube,
                light = light,
                onEdit = { if (!saving) editingMeta = true },
                onExposure = { status = camera.changeExposure(it) },
                onOverride = { choosingOverride = true },
                onResetLabels = { confirm = Confirm.RESET_LABELS },
                onRecalibrate = { if (!saving) confirm = Confirm.RECALIBRATE },
            )
            // The texts and action row are measured first (unweighted); the net/palette row takes what is left, without
            // filling it, so the status and action sit right under it and leftover space falls below them.
            BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                val side = minOf(maxHeight, maxWidth * 0.55f)
                val shownRefusal = refusal
                if (calibrated && cornerMode && shownRefusal != null) {
                    RefusalNets(Modifier.height(side).fillMaxWidth(), shownRefusal.faces, side)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!calibrated) {
                            CalibrationPanel(Modifier.size(side), centers, calibrationColor, preview)
                        } else {
                            Net(
                                Modifier.size(side),
                                entry,
                                onSelect = { if (!saving && !holding) entry = if (cornerMode) entry.tap(it) else entry.select(it) },
                            )
                        }
                        Box(Modifier.width(1.5.dp).height(side).background(MaterialTheme.colorScheme.outlineVariant))
                        Palette(
                            Modifier.height(side).weight(1f),
                            highlighted = if (calibrated) null else calibrationColor,
                            done = if (calibrated) emptySet() else centers.keys,
                        ) { color ->
                            if (!calibrated) {
                                calibrationColor = color
                                preview = null
                            } else if (!saving && !holding) {
                                val ready = entry.selected?.let { entry } ?: entry.select(entry.colors.indexOfFirst { it == null })
                                if (ready.selected == null) status = "All nine are filled: tap a sticker to change it."
                                // Story 10.28: in corner mode picking a pre-fill's own colour confirms it and keeps it PREFILLED.
                                entry = ready.set(color, keepSamePrefill = cornerMode)
                            }
                        }
                    }
                }
            }
            Text(stepLine, style = MaterialTheme.typography.labelLarge)
            refusal?.takeIf { cornerMode }?.let { shown ->
                // Never truncated: every off colour and its count stay readable.
                Text(countRefusal(shown.check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (cornerMode) {
                Text(
                    cornerGroup.name?.let { "Group folder: $it" } ?: "No group folder yet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                listOfNotNull(coaching, status, remeterNote).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lockLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val currentPreview = preview
            when {
                !calibrated && currentPreview != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)) { Text("Retake") }
                        Button(onClick = { saveSwatch(currentPreview) }, modifier = Modifier.weight(1f)) {
                            Text(if (currentPreview.warnings.isEmpty()) "Save" else "Save anyway")
                        }
                    }
                }

                !calibrated -> {
                    Button(onClick = ::sample, enabled = sampleLocks.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sample ${colorLabel(calibrationColor)}")
                    }
                }

                cornerMode && placement != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { placement = null }, enabled = !saving) { Text("Retake") }
                        Button(onClick = ::usePlacement, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Use corners") }
                    }
                }

                cornerMode -> {
                    val ready = !saving && metaReady && hasPermission
                    if (cornerShot != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::resetCorner, enabled = !saving) { Text("Retake corner") }
                            Button(onClick = ::confirmCornerFace, enabled = !saving && entry.confirmed() != null && cornerLocks.isEmpty()) {
                                Text("Confirm face")
                            }
                        }
                    } else if (refusal != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { retakeHold(1) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                                Text("Retake corner 1")
                            }
                            OutlinedButton(onClick = { retakeHold(2) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                                Text("Retake corner 2")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    overrideText = ""
                                    overriding = true
                                },
                                enabled = !saving,
                                modifier = Modifier.weight(1f),
                            ) { Text("Override…") }
                            OutlinedButton(onClick = ::recordBothAgain, enabled = !saving, modifier = Modifier.weight(1f)) {
                                Text("Record both corners again")
                            }
                        }
                        KeypointsRow(ready, { captureCorner(CornerCapture.KEYPOINTS_ONLY) }, { captureCorner(CornerCapture.NEGATIVE) })
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cornerHold <= 2) {
                                Button(onClick = { captureCorner(CornerCapture.CORNER) }, enabled = ready, modifier = Modifier.weight(1f)) {
                                    Text(cornerCaptureLabel(cornerHold, missedHold == cornerHold))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = ::recordBothAgain,
                                    enabled = !saving,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Record both corners again") }
                            }
                        }
                        KeypointsRow(ready, { captureCorner(CornerCapture.KEYPOINTS_ONLY) }, { captureCorner(CornerCapture.NEGATIVE) })
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = ::suggestAgain,
                            enabled = !saving && !holding && latestUsable,
                        ) { Text("Suggest again") }
                        Button(onClick = ::capture, enabled = captureLocks.isEmpty(), modifier = Modifier.weight(1f)) {
                            Text("Capture $selectedFace" + (qualityOverride?.let { " · $it" } ?: ""))
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= 600.dp || maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                entryPanel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                viewfinder(Modifier.fillMaxWidth().weight(0.45f))
                entryPanel(Modifier.fillMaxWidth().weight(0.55f))
            }
        }
    }

    if (editingMeta) {
        MetaDialog(
            cube,
            light,
            cornerMode,
            lightDirection,
            torchOn,
            hasFlash = hasFlash == true,
            onDismiss = { editingMeta = false },
            onContinuous =
                onContinuous?.let { open ->
                    {
                        editingMeta = false
                        open()
                    }
                },
        ) { newCube, newLight, newMode, newDirection, newTorch ->
            // A torch change is a new capture condition: a new group, like a new light direction. A new cube, light or
            // (Story 10.20) torch state is a new capture context: earlier centers and truth never carry over.
            val recalibrate = recalibrates(cube, newCube, light, newLight, torchOn, newTorch, cornerMode, newMode, hasFlash)
            if (recalibrate || newMode != cornerMode || newDirection != lightDirection) newCondition(recalibrate)
            cornerMode = newMode
            lightDirection = newDirection
            torchOn = newTorch
            cube = newCube
            light = newLight
            editingMeta = false
        }
    }
    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kind == Confirm.RESET_LABELS) "Reset labels?" else "Recalibrate?") },
            text = {
                Text(
                    if (kind == Confirm.RESET_LABELS) {
                        "Clears the nine labels of face $selectedFace."
                    } else {
                        "Clears all six calibrated colors; saved faces stay on disk."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    holdFace = null
                    if (kind == Confirm.RESET_LABELS) {
                        resetEntry()
                        status = "Labels cleared."
                    } else {
                        newCondition(recalibrate = true)
                        status = "Calibration cleared: sample all six again."
                    }
                    confirm = null
                }) { Text(if (kind == Confirm.RESET_LABELS) "Reset" else "Recalibrate") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    val overrideFor = refusal
    if (overriding && overrideFor != null) {
        val reason = overrideReason(overrideText)
        AlertDialog(
            onDismissRequest = { overriding = false },
            title = { Text("Override the count check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(countRefusal(overrideFor.check) + " Tuning and scoring skip this group unless the PO includes it.")
                    OutlinedTextField(value = overrideText, onValueChange = { overrideText = it }, label = { Text("Reason") })
                }
            },
            confirmButton = {
                TextButton(onClick = { reason?.let(::saveOverride) }, enabled = reason != null && !saving) { Text("Save with reason") }
            },
            dismissButton = { TextButton(onClick = { overriding = false }) { Text("Cancel") } },
        )
    }
    if (choosingOverride) {
        OverrideDialog(qualityOverride, onDismiss = { choosingOverride = false }) {
            qualityOverride = it
            choosingOverride = false
        }
    }
}

@Composable
private fun Viewfinder(
    modifier: Modifier,
    hasPermission: Boolean,
    onAllow: () -> Unit,
    bind: (PreviewView) -> Unit,
    calibrated: Boolean,
    corner: Boolean,
    selectedFace: Face,
    capturedFaces: List<Face>,
    prompt: String,
    onFace: (Face) -> Unit,
    detectedQuads: List<List<CornerPoint>> = emptyList(),
    placement: Placement? = null,
    onMovePoint: (Int, Double, Double) -> Unit = { _, _, _ -> },
    torch: TorchControl? = null,
) {
    Box(modifier.clipToBounds().background(UiColor.Black), contentAlignment = Alignment.Center) {
        if (hasPermission && placement != null) {
            KeypointPlacement(placement, onMovePoint, Modifier.fillMaxSize())
        } else if (hasPermission) {
            AndroidView(
                factory = { context ->
                    PreviewView(context)
                        .apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                        .also(bind)
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
            if (corner) {
                // The live detected outline over the Y anchor, in view fractions like Quick Scan's corner view.
                CornerOutline(Modifier.fillMaxSize(), detectedCornerQuads = detectedQuads)
            } else {
                FixedGrid(Modifier.fillMaxSize())
            }
        } else {
            Button(onClick = onAllow) { Text("Allow camera") }
        }
        if (hasPermission && placement == null && torch != null) {
            TorchButton(torch, Modifier.align(Alignment.TopEnd).padding(8.dp), testTag = "recorder:torch")
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (calibrated) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Face.entries.forEach { face ->
                        val center = FACE_CENTER.getValue(face)
                        val done = face in capturedFaces
                        val selected = face == selectedFace
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(swatch(center).copy(alpha = if (done) 1f else 0.45f))
                                .border(
                                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) UiColor.Magenta else UiColor.Gray),
                                    RoundedCornerShape(6.dp),
                                ).clickable { onFace(face) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                faceChipLabel(face) + if (done) " ✓" else "",
                                color = contentOn(swatch(center)),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        Text(
            prompt,
            color = UiColor.White,
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp)
                    .background(UiColor.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Story 10.19: the frozen model-input crop with the seven corners `[T, UL, LL, B, LR, UR, C]` as handles the operator
 * drags onto the cube's real corners (the labels the evaluator reads; the detector's points are kept apart).
 */
@Composable
private fun KeypointPlacement(
    placement: Placement,
    onMove: (Int, Double, Double) -> Unit,
    modifier: Modifier,
) {
    val image =
        remember(placement.shot) {
            placement.shot.modelInput?.let { bytes ->
                android.graphics.BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
            }
        }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side)) {
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                )
            }
            val points = placement.points
            val latest by rememberUpdatedState(points)
            Canvas(
                Modifier.fillMaxSize().pointerInput(placement.shot) {
                    var dragging = -1
                    detectDragGestures(
                        onDragStart = { start ->
                            dragging =
                                latest.indices.minByOrNull { i ->
                                    val p = latest[i]
                                    hypot(p[0] * size.width - start.x, p[1] * size.height - start.y).toDouble()
                                } ?: -1
                        },
                        onDragEnd = { dragging = -1 },
                    ) { change, amount ->
                        change.consume()
                        if (dragging >= 0) onMove(dragging, amount.x / size.width.toDouble(), amount.y / size.height.toDouble())
                    }
                },
            ) {
                val pts = points.map { Offset((it[0] * size.width).toFloat(), (it[1] * size.height).toFloat()) }
                // The outline the seven corners imply: hexagon T-UR-LR-B-LL-UL and the three edges to C.
                listOf(0 to 5, 5 to 4, 4 to 3, 3 to 2, 2 to 1, 1 to 0, 6 to 0, 6 to 2, 6 to 4).forEach { (a, b) ->
                    drawLine(UiColor.Magenta, pts[a], pts[b], 2.dp.toPx())
                }
                placement.detector?.forEach { p ->
                    drawCircle(UiColor.Cyan, 3.dp.toPx(), Offset((p[0] * size.width).toFloat(), (p[1] * size.height).toFloat()))
                }
                pts.forEach {
                    drawCircle(
                        UiColor.Yellow,
                        7.dp.toPx(),
                        it,
                        style =
                            androidx.compose.ui.graphics.drawscope
                                .Stroke(2.dp.toPx()),
                    )
                }
            }
            points.forEachIndexed { index, p ->
                Text(
                    KEYPOINT_NAMES[index],
                    color = UiColor.Yellow,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.offset(side * p[0].toFloat() + 8.dp, side * p[1].toFloat() - 8.dp),
                )
            }
        }
    }
}

/** Header: cube/light summary with an edit dialog, plus the rare controls, apart from the net and palette. */
@Composable
private fun HeaderRow(
    cube: String,
    light: String,
    onEdit: () -> Unit,
    onExposure: (Int) -> Unit,
    onOverride: () -> Unit,
    onResetLabels: () -> Unit,
    onRecalibrate: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
            Column {
                // Cube and light wrap rather than truncate; only the device model may be cut short.
                Text("${cube.ifBlank { "cube?" }} · ${light.ifBlank { "light?" }}")
                Text(Build.MODEL, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { onExposure(-1) }) { Text("EV−") }
        TextButton(onClick = { onExposure(1) }) { Text("EV+") }
        TextButton(onClick = { onExposure(0) }) { Text("Re-meter") }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Quality override…") }, onClick = {
                    menu = false
                    onOverride()
                })
                DropdownMenuItem(text = { Text("Reset labels…") }, onClick = {
                    menu = false
                    onResetLabels()
                })
                DropdownMenuItem(text = { Text("Recalibrate…") }, onClick = {
                    menu = false
                    onRecalibrate()
                })
            }
        }
    }
}

/** Corner mode's supplemental captures: a keypoint-only positive or a no-cube negative. */
@Composable
private fun KeypointsRow(
    ready: Boolean,
    onKeypoints: () -> Unit,
    onNegative: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onKeypoints, enabled = ready, modifier = Modifier.weight(1f)) {
            Text("Keypoints only")
        }
        OutlinedButton(onClick = onNegative, enabled = ready, modifier = Modifier.weight(1f)) {
            Text("No cube (negative)")
        }
    }
}

/**
 * Story 10.28: the faces of a refused group holding pre-filled stickers of over-counted colours, each a read-only net
 * captioned with its hold and centre colour, the stickers to check ringed (never tinted); scrolls sideways.
 */
@Composable
private fun RefusalNets(
    modifier: Modifier,
    faces: List<RefusalFace>,
    side: androidx.compose.ui.unit.Dp,
) {
    if (faces.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No pre-filled stickers of the over-counted colours.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val netSide = (side - 28.dp).coerceAtLeast(48.dp)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        faces.forEach { face ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Corner ${face.hold} · ${colorLabel(face.colors[FaceSample.CENTER_INDEX])} face",
                    style = MaterialTheme.typography.labelMedium,
                )
                Net(
                    Modifier.size(netSide),
                    TruthEntry(colors = face.colors, sources = face.sources.ifEmpty { List(face.colors.size) { null } }),
                    onSelect = null,
                    ringed = face.ringed,
                )
            }
        }
    }
}

/**
 * The current face's 3×3 net: tap a sticker to select it ([onSelect] null: read-only). Pre-filled stickers not checked
 * yet are italic, checked ones upright, entered ones bold; [ringed] stickers get a magenta ring with the fill unchanged.
 */
@Composable
internal fun Net(
    modifier: Modifier,
    entry: TruthEntry,
    onSelect: ((Int) -> Unit)?,
    ringed: Set<Int> = emptySet(),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    val color = entry.colors[index]
                    val selected = entry.selected == index
                    val prefilled = entry.sources[index] == TruthSource.PREFILLED
                    val checked = entry.reviewed.getOrElse(index) { false }
                    val fill = color?.let(::swatch) ?: UiColor.LightGray
                    val border =
                        when {
                            selected -> BorderStroke(4.dp, UiColor.Magenta)
                            index in ringed -> BorderStroke(3.dp, UiColor.Magenta)
                            else -> BorderStroke(1.dp, UiColor.DarkGray)
                        }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(fill)
                            .border(border, RoundedCornerShape(4.dp))
                            .then(if (onSelect != null) Modifier.clickable { onSelect(index) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            color?.let(::glyph) ?: "?",
                            color = contentOn(fill),
                            fontWeight = if (prefilled) FontWeight.Normal else FontWeight.Bold,
                            fontStyle = if (prefilled && !checked) FontStyle.Italic else FontStyle.Normal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

/** 2×3 palette in editor order with glyphs. */
@Composable
internal fun Palette(
    modifier: Modifier,
    highlighted: Color?,
    done: Set<Color>,
    onColor: (Color) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PALETTE.chunked(2).forEach { pair ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { color ->
                    val fill = swatch(color)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill)
                            .border(
                                BorderStroke(
                                    if (color ==
                                        highlighted
                                    ) {
                                        4.dp
                                    } else {
                                        1.dp
                                    },
                                    if (color == highlighted) UiColor.Magenta else UiColor.Gray,
                                ),
                                RoundedCornerShape(6.dp),
                            ).clickable { onColor(color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            glyph(color) + if (color in done) "✓" else "",
                            color = contentOn(fill),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Calibration area: the sampled swatch with its Lab values and warnings, or the saved centers so far. */
@Composable
private fun CalibrationPanel(
    modifier: Modifier,
    centers: Map<Color, Lab>,
    calibrationColor: Color,
    preview: SwatchPreview?,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (preview == null) {
            Text(
                "Put a plain ${colorLabel(
                    calibrationColor,
                )} sticker (no logo) in the center cell, then Sample. Tap the palette to pick another color.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PALETTE.forEach { color ->
                    val saved = centers[color]
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(saved?.let(::labToUi) ?: UiColor.LightGray)
                            .border(1.dp, UiColor.DarkGray),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            glyph(color),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                contentOn(saved?.let(::labToUi) ?: UiColor.LightGray),
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(44.dp).background(labToUi(preview.swatch)).border(1.dp, UiColor.DarkGray))
                Column {
                    Text(colorLabel(preview.color), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "L %.0f a %.0f b %.0f · C %.0f".format(
                            preview.swatch.l,
                            preview.swatch.a,
                            preview.swatch.b,
                            chroma(preview.swatch),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (preview.warnings.isEmpty()) {
                Text("Looks plausible.", style = MaterialTheme.typography.bodySmall)
            } else {
                preview.warnings.forEach { Text(it.describe(), style = MaterialTheme.typography.bodySmall, color = UiColor(0xFFB3261E)) }
            }
        }
    }
}

@Composable
private fun MetaDialog(
    cube: String,
    light: String,
    cornerMode: Boolean,
    lightDirection: LightDirection?,
    torchOn: Boolean,
    hasFlash: Boolean,
    onDismiss: () -> Unit,
    onContinuous: (() -> Unit)? = null,
    onSave: (String, String, Boolean, LightDirection?, Boolean) -> Unit,
) {
    var newTorch by remember { mutableStateOf(torchOn) }
    var newCube by remember { mutableStateOf(cube) }
    var newLight by remember { mutableStateOf(light) }
    var newMode by remember { mutableStateOf(cornerMode) }
    var newDirection by remember { mutableStateOf(lightDirection) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cube and light") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newCube,
                    onValueChange = { newCube = it },
                    label = { Text("Cube (e.g. stla)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newLight,
                    onValueChange = { newLight = it },
                    label = { Text("Light (e.g. studio)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = newMode, onCheckedChange = { newMode = it })
                    Text("Corner mode")
                }
                // Story 10.34: the third mode, shown only when the continuous engine is composed.
                onContinuous?.let { open -> OutlinedButton(onClick = open) { Text("Continuous session…") } }
                if (newMode) {
                    // Story 10.19: the group manifest records where the light comes from; the replay scores front-lit groups.
                    Text("Light direction", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        LightDirection.FRONT to "Front (light from the camera side)",
                        LightDirection.BACK to "Back (light behind the cube)",
                        LightDirection.SIDE to "Side (light from one side)",
                    ).forEach { (direction, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = newDirection == direction, onClick = {
                                newDirection =
                                    direction
                            }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = newDirection == direction, onClick = { newDirection = direction })
                            Text(label)
                        }
                    }
                    if (hasFlash) {
                        // Story 10.19: the manifest and every fixture record the torch, so torch-on groups stay apart.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = newTorch, onCheckedChange = { newTorch = it })
                            Text("Torch on")
                        }
                    }
                }
                Text(
                    if (hasFlash) {
                        "Changing the cube, light or torch clears the calibration."
                    } else {
                        "Changing the cube or light clears the calibration."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(newCube, newLight, newMode, newDirection, newTorch) },
                enabled = !newMode || newDirection != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OverrideDialog(
    current: CaptureQuality?,
    onDismiss: () -> Unit,
    onConfirm: (CaptureQuality?) -> Unit,
) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quality override") },
        text = {
            Column {
                Text(
                    "Marks the next capture as unusable. It shows on the Capture button and clears after a capture.",
                    style = MaterialTheme.typography.bodySmall,
                )
                listOf(null, CaptureQuality.BLURRED, CaptureQuality.MISALIGNED, CaptureQuality.INCOMPLETE).forEach { quality ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == quality, onClick = { choice = quality }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == quality, onClick = { choice = quality })
                        Text(quality?.name ?: "None")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FixedGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val side = minOf(size.width, size.height) * 0.8f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        for (index in 1..2) {
            val x = left + side * index / 3
            drawLine(UiColor.White, Offset(x, top), Offset(x, top + side), stroke)
            val y = top + side * index / 3
            drawLine(UiColor.White, Offset(left, y), Offset(left + side, y), stroke)
        }
        drawRect(
            UiColor.White,
            topLeft = Offset(left, top),
            size =
                androidx.compose.ui.geometry
                    .Size(side, side),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(stroke),
        )
        // Calibration ring of the center cell, so the operator sees where the swatch is sampled.
        val cell = side / 3
        val middle = Offset(left + 1.5f * cell, top + 1.5f * cell)
        drawCircle(
            UiColor.White.copy(alpha = 0.7f),
            radius = cell * RING_RADIUS_FRACTION.toFloat(),
            center = middle,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(1.dp.toPx()),
        )
    }
}

private fun swatch(color: Color): UiColor =
    when (color) {
        Color.WHITE -> UiColor(0xFFFFFFFF)
        Color.YELLOW -> UiColor(0xFFFFD500)
        Color.GREEN -> UiColor(0xFF009E60)
        Color.BLUE -> UiColor(0xFF0051BA)
        Color.RED -> UiColor(0xFFC41E3A)
        Color.ORANGE -> UiColor(0xFFFF5800)
    }

private fun glyph(color: Color) = color.name.take(1)

private fun contentOn(fill: UiColor) = if (fill.luminance() > 0.4f) UiColor.Black else UiColor.White

/** CIELAB (D65) to an sRGB display color, for the calibration swatch preview only. */
private fun labToUi(lab: Lab): UiColor {
    val fy = (lab.l + 16.0) / 116.0
    val fx = fy + lab.a / 500.0
    val fz = fy - lab.b / 200.0

    fun inverse(value: Double) = if (value.pow(3) > 0.0088564517) value.pow(3) else (value - 16.0 / 116.0) / 7.787037
    val x = inverse(fx) * 0.95047
    val y = inverse(fy)
    val z = inverse(fz) * 1.08883

    fun gamma(linear: Double): Float {
        val clamped = linear.coerceIn(0.0, 1.0)
        return (if (clamped <= 0.0031308) 12.92 * clamped else 1.055 * clamped.pow(1 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    }
    return UiColor(
        gamma(3.2404542 * x - 1.5371385 * y - 0.4985314 * z),
        gamma(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z),
        gamma(0.0556434 * x - 0.2040259 * y + 1.0572252 * z),
    )
}
