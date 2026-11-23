package com.devoid.keysync.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.devoid.keysync.domain.TrajectoryItemKind
import com.devoid.keysync.domain.TrajectoryItemSnapshot
import com.devoid.keysync.domain.TrajectoryKeyEvent
import com.devoid.keysync.domain.TrajectoryPointSample
import com.devoid.keysync.domain.TrajectoryPointerTrack
import com.devoid.keysync.domain.TrajectoryRecording
import com.devoid.keysync.util.keyCodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把全量轨迹录制渲染成 720p / 30fps 的 H.264 MP4。
 *
 * 画面是「映射层的全屏回放」：按停止录制时的布局画出所有按钮，并把每个触点的
 * 运动轨迹、按下/长按/点击波纹、宏触点颜色、物理按键条一起画出来。
 * 不录游戏画面、不录声音，也不需要录屏权限。
 */
object TrajectoryVideoRenderer {
    const val FPS = 30

    private const val MAX_DURATION_MS = 60_000L
    private const val MIN_DURATION_MS = 600L

    private const val MACRO_POINTER_BASE = 1_000_000
    private const val EXTRA_TAP_POINTER_BASE = 1_200_000

    private const val BG = 0xFF090C11.toInt()
    private const val STAGE = 0xFF10151C.toInt()
    private const val STAGE_BORDER = 0xFF273242.toInt()
    private const val GRID = 0xFF1A2230.toInt()
    private const val TEXT = 0xFFE6EAF0.toInt()
    private const val MUTED = 0xFF8A94A6.toInt()
    private const val ITEM_BORDER = 0xFF44546A.toInt()
    private const val ITEM_FILL = 0x14FFFFFF
    private const val ITEM_PRESSED = 0x552FA8FF
    private const val PRESS_COLOR = 0xFF5BD68A.toInt()
    private const val TRACK_COLOR = 0xFF4EA1FF.toInt()
    private const val MACRO_COLOR = 0xFFC77DFF.toInt()
    private const val TAP_COLOR = 0xFFFFB74D.toInt()
    private const val ACCENT = 0xFF4EA1FF.toInt()

    suspend fun render(
        recording: TrajectoryRecording,
        items: List<TrajectoryItemSnapshot>,
        presetName: String,
        outFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            renderBlocking(recording, items, presetName, outFile, onProgress)
        }.getOrElse {
            outFile.delete()
            false
        }
    }

    private fun renderBlocking(
        recording: TrajectoryRecording,
        items: List<TrajectoryItemSnapshot>,
        presetName: String,
        outFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val durationMs = recording.durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val portrait = recording.viewportHeight > recording.viewportWidth
        val width = if (portrait) 720 else 1280
        val height = if (portrait) 1280 else 720
        val frames = max(1, (durationMs / 1000.0 * FPS).roundToInt())
        outFile.parentFile?.mkdirs()

        val stage = stageTransform(recording, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)
        val paints = Paints()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, if (portrait) 6_000_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        val muxState = MuxState()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var frame = 0
            while (frame < frames) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val image = codec.getInputImage(inputIndex) ?: return false
                    val tMs = frame * 1000L / FPS
                    drawFrame(
                        canvas, paints, recording, items, presetName,
                        tMs, durationMs, width, height, stage,
                    )
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    fillYuv(image, pixels, width, height)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        width * height * 3 / 2,
                        frame * 1_000_000L / FPS,
                        0
                    )
                    frame++
                    if (frame % 10 == 0 || frame == frames) {
                        onProgress((frame * 100 / frames).coerceIn(0, 100))
                    }
                }
                drain(codec, muxer, bufferInfo, false, muxState)
            }

            val eosIndex = codec.dequeueInputBuffer(100_000)
            if (eosIndex >= 0) {
                codec.queueInputBuffer(
                    eosIndex,
                    0,
                    0,
                    frames * 1_000_000L / FPS,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(codec, muxer, bufferInfo, true, muxState)
            return muxState.started
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxState.started) muxer.stop() }
            runCatching { muxer.release() }
            bitmap.recycle()
        }
    }

    private class MuxState {
        var track = -1
        var started = false
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        state: MuxState,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.nanoTime() > deadline) return
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    state.track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.started = true
                }

                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && state.started) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(state.track, buffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun fillYuv(image: android.media.Image, pixels: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (row in 0 until height) {
            val yBase = row * yPlane.rowStride
            val uvRow = row / 2
            val uBase = uvRow * uPlane.rowStride
            val vBase = uvRow * vPlane.rowStride
            val evenRow = (row and 1) == 0
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuffer.put(yBase + col * yPlane.pixelStride, y.toByte())

                if (evenRow && (col and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuffer.put(uBase + (col / 2) * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                    vBuffer.put(vBase + (col / 2) * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private class Paints {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    /** 屏幕像素坐标 → 视频像素坐标的缩放与偏移。 */
    private class Stage(
        val scale: Float,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        fun x(value: Float): Float = left + value * scale
        fun y(value: Float): Float = top + value * scale
        fun r(value: Float): Float = value * scale
    }

    private fun stageTransform(recording: TrajectoryRecording, videoWidth: Int, videoHeight: Int): Stage {
        val viewportWidth = recording.viewportWidth.takeIf { it > 1f } ?: videoWidth.toFloat()
        val viewportHeight = recording.viewportHeight.takeIf { it > 1f } ?: videoHeight.toFloat()
        val marginX = if (videoWidth < 900) 28f else 40f
        val headerHeight = 88f
        val footerHeight = 92f
        val availableWidth = videoWidth - marginX * 2f
        val availableHeight = videoHeight - headerHeight - footerHeight
        val scale = min(availableWidth / viewportWidth, availableHeight / viewportHeight)
        val width = viewportWidth * scale
        val height = viewportHeight * scale
        return Stage(
            scale = scale,
            left = (videoWidth - width) / 2f,
            top = headerHeight + (availableHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    private fun drawFrame(
        canvas: Canvas,
        p: Paints,
        recording: TrajectoryRecording,
        items: List<TrajectoryItemSnapshot>,
        presetName: String,
        tMs: Long,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int,
        stage: Stage,
    ) {
        canvas.drawColor(BG)
        drawHeader(canvas, p, recording, presetName, durationMs, videoWidth)
        drawStage(canvas, p, stage)

        canvas.save()
        canvas.clipRect(stage.left, stage.top, stage.left + stage.width, stage.top + stage.height)
        drawItems(canvas, p, items, recording.tracks, tMs, stage)
        drawTracks(canvas, p, recording.tracks, tMs, stage)
        canvas.restore()

        drawKeyChips(canvas, p, recording.keys, tMs, stage)
        drawFooter(canvas, p, tMs, durationMs, videoWidth, videoHeight)
    }

    private fun drawHeader(
        canvas: Canvas,
        p: Paints,
        recording: TrajectoryRecording,
        presetName: String,
        durationMs: Long,
        videoWidth: Int,
    ) {
        val margin = if (videoWidth < 900) 28f else 40f
        p.text.textAlign = Paint.Align.LEFT
        p.text.color = TEXT
        p.text.textSize = if (videoWidth < 900) 26f else 32f
        canvas.drawText("映射轨迹回放", margin, 40f, p.text)

        p.text.color = MUTED
        p.text.textSize = if (videoWidth < 900) 15f else 17f
        val subtitle = "预设 ${presetName.ifBlank { "未命名" }} · " +
            "时长 ${formatTime(durationMs)} · 触点 ${recording.tracks.size} · 按键 ${recording.keys.size}"
        canvas.drawText(subtitle, margin, 68f, p.text)
    }

    private fun drawStage(canvas: Canvas, p: Paints, stage: Stage) {
        p.fill.color = STAGE
        canvas.drawRoundRect(
            stage.left, stage.top, stage.left + stage.width, stage.top + stage.height,
            20f, 20f, p.fill
        )

        // 细网格：方便看出触点在屏幕上的相对位置。
        p.grid.color = GRID
        val columns = 6
        val rows = 10
        for (i in 1 until columns) {
            val x = stage.left + stage.width * i / columns
            canvas.drawLine(x, stage.top + 2f, x, stage.top + stage.height - 2f, p.grid)
        }
        for (i in 1 until rows) {
            val y = stage.top + stage.height * i / rows
            canvas.drawLine(stage.left + 2f, y, stage.left + stage.width - 2f, y, p.grid)
        }

        p.line.color = STAGE_BORDER
        p.line.strokeWidth = 2f
        canvas.drawRoundRect(
            stage.left, stage.top, stage.left + stage.width, stage.top + stage.height,
            20f, 20f, p.line
        )
    }

    private fun isItemPressed(
        item: TrajectoryItemSnapshot,
        tracks: List<TrajectoryPointerTrack>,
        tMs: Long,
    ): Boolean {
        for (track in tracks) {
            if (!track.isActiveAt(tMs)) continue
            val point = track.positionAt(tMs) ?: continue
            val dx = point.x - item.centerX
            val dy = point.y - item.centerY
            if (item.kind == TrajectoryItemKind.WASD) {
                val rx = item.radiusX.coerceAtLeast(1f)
                val ry = item.radiusY.coerceAtLeast(1f)
                val nx = dx / rx
                val ny = dy / ry
                if (nx * nx + ny * ny <= 1.35f) return true
            } else {
                if (abs(dx) <= item.radiusX * 1.25f && abs(dy) <= item.radiusY * 1.25f) return true
            }
        }
        return false
    }

    private fun drawItems(
        canvas: Canvas,
        p: Paints,
        items: List<TrajectoryItemSnapshot>,
        tracks: List<TrajectoryPointerTrack>,
        tMs: Long,
        stage: Stage,
    ) {
        for (item in items) {
            val cx = stage.x(item.centerX)
            val cy = stage.y(item.centerY)
            val rx = max(6f, stage.r(item.radiusX))
            val ry = max(6f, stage.r(item.radiusY))
            val pressed = isItemPressed(item, tracks, tMs)

            if (item.kind == TrajectoryItemKind.WASD) {
                p.fill.color = if (pressed) ITEM_PRESSED else ITEM_FILL
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p.fill)

                p.line.color = if (pressed) PRESS_COLOR else ITEM_BORDER
                p.line.strokeWidth = if (pressed) 3.5f else 2f
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p.line)

                p.line.color = ITEM_BORDER
                p.line.alpha = 90
                p.line.strokeWidth = 1f
                canvas.drawLine(cx - rx, cy, cx + rx, cy, p.line)
                canvas.drawLine(cx, cy - ry, cx, cy + ry, p.line)
                p.line.alpha = 255

                if (rx > 26f && ry > 26f) {
                    val size = min(18f, max(11f, rx * 0.18f))
                    drawCenteredText(canvas, p, "W", cx, cy - ry + 20f, size, MUTED)
                    drawCenteredText(canvas, p, "S", cx, cy + ry - 8f, size, MUTED)
                    drawCenteredText(canvas, p, "A", cx - rx + 18f, cy + 6f, size, MUTED)
                    drawCenteredText(canvas, p, "D", cx + rx - 18f, cy + 6f, size, MUTED)
                }
            } else {
                val radius = min(rx, ry)
                p.fill.color = if (pressed) ITEM_PRESSED else ITEM_FILL
                canvas.drawRoundRect(
                    cx - rx, cy - ry, cx + rx, cy + ry,
                    radius * 0.42f, radius * 0.42f, p.fill
                )
                p.line.color = if (pressed) PRESS_COLOR else ITEM_BORDER
                p.line.strokeWidth = if (pressed) 3f else 1.8f
                canvas.drawRoundRect(
                    cx - rx, cy - ry, cx + rx, cy + ry,
                    radius * 0.42f, radius * 0.42f, p.line
                )
                if (radius >= 13f) {
                    val size = min(radius * 0.62f, 22f).coerceAtLeast(10f)
                    val label = if (item.label.length > 6) item.label.take(6) else item.label
                    drawCenteredText(canvas, p, label, cx, cy, size, TEXT)
                }
            }
        }
    }

    private fun drawTracks(
        canvas: Canvas,
        p: Paints,
        tracks: List<TrajectoryPointerTrack>,
        tMs: Long,
        stage: Stage,
    ) {
        for (track in tracks) {
            if (track.downTimeMs > tMs) continue
            val endTime = min(tMs, track.upTimeMs ?: tMs)
            val points = ArrayList<TrajectoryPointSample>()
            for (sample in track.samples) {
                if (sample.timeMs > endTime) break
                points += sample
            }
            if (points.isEmpty()) continue

            val color = trackColor(track.pointerId)

            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { index, sample ->
                    if (index == 0) path.moveTo(stage.x(sample.x), stage.y(sample.y))
                    else path.lineTo(stage.x(sample.x), stage.y(sample.y))
                }
                p.line.color = color
                p.line.alpha = 55
                p.line.strokeWidth = 2.2f
                canvas.drawPath(path, p.line)
            }

            // 最近 1.5 秒的轨迹高亮，方便看清「此刻」触点在怎样运动。
            val recentStart = endTime - 1_500L
            val recent = points.filter { it.timeMs >= recentStart }
            if (recent.size >= 2) {
                val path = Path()
                recent.forEachIndexed { index, sample ->
                    if (index == 0) path.moveTo(stage.x(sample.x), stage.y(sample.y))
                    else path.lineTo(stage.x(sample.x), stage.y(sample.y))
                }
                p.line.color = color
                p.line.alpha = 255
                p.line.strokeWidth = 3.4f
                canvas.drawPath(path, p.line)
            } else if (recent.size == 1) {
                p.fill.color = color
                p.fill.alpha = 180
                canvas.drawCircle(stage.x(recent.first().x), stage.y(recent.first().y), 3f, p.fill)
                p.fill.alpha = 255
            }

            val down = points.first()
            val sinceDown = tMs - track.downTimeMs
            if (sinceDown in 0..280) {
                val progress = sinceDown / 280f
                p.line.color = color
                p.line.alpha = ((1f - progress) * 220f).toInt().coerceIn(0, 255)
                p.line.strokeWidth = 2.5f
                canvas.drawCircle(stage.x(down.x), stage.y(down.y), 8f + progress * 26f, p.line)
                p.line.alpha = 255
            }

            val anchor = points.last()
            val ax = stage.x(anchor.x)
            val ay = stage.y(anchor.y)
            if (track.isActiveAt(tMs)) {
                val holdMs = tMs - track.downTimeMs
                // 长按：触点外圈持续扩散，明显区别于点击。
                if (holdMs > 450) {
                    val wave = (sin((holdMs % 900) / 900.0 * 2 * PI).toFloat() * 0.5f + 0.5f)
                    p.line.color = color
                    p.line.alpha = (70f + wave * 90f).toInt().coerceIn(0, 255)
                    p.line.strokeWidth = 2f
                    canvas.drawCircle(ax, ay, 13f + wave * 7f, p.line)
                    p.line.alpha = 255
                }
                p.fill.color = color
                canvas.drawCircle(ax, ay, 7f, p.fill)
                p.fill.color = Color.WHITE
                canvas.drawCircle(ax, ay, 3f, p.fill)
            } else {
                val upTime = track.upTimeMs ?: continue
                val sinceUp = tMs - upTime
                if (sinceUp in 0..320) {
                    val progress = sinceUp / 320f
                    p.line.color = color
                    p.line.alpha = ((1f - progress) * 200f).toInt().coerceIn(0, 255)
                    p.line.strokeWidth = 2f
                    canvas.drawCircle(ax, ay, 10f, p.line)
                    p.line.alpha = 255
                }
            }
        }
    }

    private fun trackColor(pointerId: Int): Int = when {
        pointerId >= EXTRA_TAP_POINTER_BASE -> TAP_COLOR
        pointerId >= MACRO_POINTER_BASE -> MACRO_COLOR
        else -> TRACK_COLOR
    }

    /** 按键条：屏幕左上角显示最近按过的物理键/鼠标键，按下时高亮。 */
    private fun drawKeyChips(
        canvas: Canvas,
        p: Paints,
        keys: List<TrajectoryKeyEvent>,
        tMs: Long,
        stage: Stage,
    ) {
        val latestByKey = LinkedHashMap<Int, TrajectoryKeyEvent>()
        for (event in keys) {
            if (event.timeMs > tMs) break
            latestByKey.remove(event.keyCode)
            latestByKey[event.keyCode] = event
        }
        val chips = latestByKey.values.toList().takeLast(10)
        if (chips.isEmpty()) return

        val chipHeight = 30f
        val chipGap = 8f
        val rowGap = 6f
        var x = stage.left + 16f
        var y = stage.top + 16f
        val maxRight = stage.left + stage.width - 16f

        p.text.textAlign = Paint.Align.LEFT
        p.text.textSize = 15f
        for (chip in chips) {
            val label = chip.keyCode.keyCodeToString()
            val chipWidth = p.text.measureText(label) + 22f
            if (x + chipWidth > maxRight && x > stage.left + 16f) {
                x = stage.left + 16f
                y += chipHeight + rowGap
                if (y + chipHeight > stage.top + 16f + (chipHeight + rowGap) * 2) break
            }

            p.fill.color = 0xB30A0E14.toInt()
            canvas.drawRoundRect(x, y, x + chipWidth, y + chipHeight, 8f, 8f, p.fill)
            p.line.color = if (chip.pressed) PRESS_COLOR else 0x55FFFFFF
            p.line.strokeWidth = 1.5f
            canvas.drawRoundRect(x, y, x + chipWidth, y + chipHeight, 8f, 8f, p.line)
            p.text.color = if (chip.pressed) TEXT else MUTED
            canvas.drawText(label, x + 11f, y + chipHeight / 2f + 5f, p.text)
            x += chipWidth + chipGap
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        p: Paints,
        tMs: Long,
        durationMs: Long,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        val margin = if (videoWidth < 900) 28f else 40f
        val barY = videoHeight - 46f

        // 图例
        p.text.textAlign = Paint.Align.LEFT
        p.text.textSize = 14f
        p.text.color = MUTED
        val legendY = barY - 18f
        var legendX = margin
        legendX = drawLegend(canvas, p, legendX, legendY, TRACK_COLOR, "普通触点")
        legendX = drawLegend(canvas, p, legendX, legendY, MACRO_COLOR, "宏")
        drawLegend(canvas, p, legendX, legendY, TAP_COLOR, "连点")

        val progress = if (durationMs <= 0) 0f else (tMs.toFloat() / durationMs).coerceIn(0f, 1f)
        p.fill.color = GRID
        canvas.drawRoundRect(margin, barY, videoWidth - margin, barY + 8f, 4f, 4f, p.fill)
        p.fill.color = ACCENT
        canvas.drawRoundRect(
            margin, barY,
            margin + (videoWidth - margin * 2f) * progress, barY + 8f,
            4f, 4f, p.fill
        )

        p.text.textSize = 14f
        p.text.color = MUTED
        canvas.drawText(formatTime(tMs), margin, videoHeight - 16f, p.text)
        p.text.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatTime(durationMs), videoWidth - margin, videoHeight - 16f, p.text)
        p.text.textAlign = Paint.Align.LEFT
    }

    private fun drawLegend(
        canvas: Canvas,
        p: Paints,
        x: Float,
        y: Float,
        color: Int,
        label: String,
    ): Float {
        p.fill.color = color
        canvas.drawCircle(x + 4f, y - 5f, 4f, p.fill)
        p.text.color = MUTED
        canvas.drawText(label, x + 12f, y, p.text)
        return x + 12f + p.text.measureText(label) + 18f
    }

    private fun drawCenteredText(
        canvas: Canvas,
        p: Paints,
        text: String,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int,
    ) {
        p.text.textAlign = Paint.Align.CENTER
        p.text.textSize = size
        p.text.color = color
        canvas.drawText(text, centerX, centerY + size * 0.36f, p.text)
        p.text.textAlign = Paint.Align.LEFT
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (abs(ms) / 1000.0)
        return String.format(Locale.US, "%.1fs", totalSeconds)
    }
}
