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
import com.devoid.keysync.domain.TrajectoryRecording
import com.devoid.keysync.domain.WasdTrajectorySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把轨迹录制渲染成 720p / 30fps 的 H.264 MP4。
 *
 * 画面左边是 WASD 摇杆向量（不是游戏内角色位置），右边是射击模式鼠标增量积分出的
 * 视角路径。渲染完全离线，不需要录屏权限，也不会把游戏画面录进去。
 */
object TrajectoryVideoRenderer {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30

    private const val BG = 0xFF0E1116.toInt()
    private const val PANEL = 0xFF151B24.toInt()
    private const val GRID = 0xFF232C38.toInt()
    private const val TEXT = 0xFFE6EAF0.toInt()
    private const val MUTED = 0xFF8A94A6.toInt()
    private const val WASD_COLOR = 0xFF4EA1FF.toInt()
    private const val MOUSE_COLOR = 0xFFFF8A4C.toInt()
    private const val OK_COLOR = 0xFF5BD68A.toInt()

    suspend fun render(
        recording: TrajectoryRecording,
        presetName: String,
        outFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            renderBlocking(recording, presetName, outFile, onProgress)
        }.getOrElse {
            outFile.delete()
            false
        }
    }

    private fun renderBlocking(
        recording: TrajectoryRecording,
        presetName: String,
        outFile: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val durationMs = recording.durationMs.coerceIn(1_000L, 60_000L)
        val frames = max(1, (durationMs / 1000.0 * FPS).roundToInt())
        outFile.parentFile?.mkdirs()

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(WIDTH * HEIGHT)
        val mousePath = recording.mousePath()
        val paints = Paints()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
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
                    drawFrame(canvas, paints, recording, mousePath, presetName, tMs, durationMs)
                    bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    fillYuv(image, pixels)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        WIDTH * HEIGHT * 3 / 2,
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

    private fun fillYuv(image: android.media.Image, pixels: IntArray) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (row in 0 until HEIGHT) {
            val yBase = row * yPlane.rowStride
            val uvRow = row / 2
            val uBase = uvRow * uPlane.rowStride
            val vBase = uvRow * vPlane.rowStride
            val evenRow = (row and 1) == 0
            for (col in 0 until WIDTH) {
                val color = pixels[row * WIDTH + col]
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
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    private fun drawFrame(
        canvas: Canvas,
        p: Paints,
        recording: TrajectoryRecording,
        mousePath: List<com.devoid.keysync.domain.TrajectoryPoint>,
        presetName: String,
        tMs: Long,
        durationMs: Long,
    ) {
        canvas.drawColor(BG)

        p.text.textSize = 30f
        p.text.color = TEXT
        canvas.drawText("轨迹回放", 48f, 56f, p.text)
        p.text.textSize = 18f
        p.text.color = MUTED
        canvas.drawText(
            "预设：${presetName.ifBlank { "未命名" }}    时长：${formatTime(durationMs)}",
            48f,
            86f,
            p.text
        )

        drawWasdPanel(canvas, p, recording.wasd, tMs)
        drawMousePanel(canvas, p, mousePath, tMs)

        val progress = if (durationMs <= 0) 0f else (tMs.toFloat() / durationMs).coerceIn(0f, 1f)
        p.fill.color = GRID
        canvas.drawRoundRect(48f, 664f, WIDTH - 48f, 672f, 4f, 4f, p.fill)
        p.fill.color = WASD_COLOR
        canvas.drawRoundRect(48f, 664f, 48f + (WIDTH - 96f) * progress, 672f, 4f, 4f, p.fill)
        p.text.textSize = 16f
        p.text.color = MUTED
        canvas.drawText(formatTime(tMs), 48f, 700f, p.text)
        canvas.drawText(formatTime(durationMs), WIDTH - 48f - 52f, 700f, p.text)
    }

    private fun drawWasdPanel(
        canvas: Canvas,
        p: Paints,
        samples: List<WasdTrajectorySample>,
        tMs: Long,
    ) {
        val left = 64f
        val top = 120f
        val size = 480f
        val centerX = left + size / 2f
        val centerY = top + size / 2f
        val half = size / 2f
        val domain = 1.4f

        p.fill.color = PANEL
        canvas.drawRoundRect(left, top, left + size, top + size, 20f, 20f, p.fill)

        p.grid.color = GRID
        for (i in 0..4) {
            val x = left + size * i / 4f
            val y = top + size * i / 4f
            canvas.drawLine(x, top, x, top + size, p.grid)
            canvas.drawLine(left, y, left + size, y, p.grid)
        }
        p.grid.color = 0xFF3A4658.toInt()
        canvas.drawCircle(centerX, centerY, half / domain, p.grid)

        p.text.textSize = 20f
        p.text.color = MUTED
        canvas.drawText("W", centerX - 8f, top + 26f, p.text)
        canvas.drawText("S", centerX - 8f, top + size - 12f, p.text)
        canvas.drawText("A", left + 12f, centerY + 7f, p.text)
        canvas.drawText("D", left + size - 28f, centerY + 7f, p.text)

        fun px(x: Float) = centerX + x / domain * half
        fun py(y: Float) = centerY + y / domain * half

        val visible = samples.filter { it.timeMs <= tMs }
        if (visible.isNotEmpty()) {
            val all = Path()
            visible.forEachIndexed { index, s ->
                if (index == 0) all.moveTo(px(s.x), py(s.y)) else all.lineTo(px(s.x), py(s.y))
            }
            p.line.color = WASD_COLOR
            p.line.alpha = 60
            canvas.drawPath(all, p.line)
            p.line.alpha = 255

            val recent = visible.filter { it.timeMs >= tMs - 2_000 }
            if (recent.size > 1) {
                val path = Path()
                recent.forEachIndexed { index, s ->
                    if (index == 0) path.moveTo(px(s.x), py(s.y)) else path.lineTo(px(s.x), py(s.y))
                }
                canvas.drawPath(path, p.line)
            }
            val last = visible.last()
            p.fill.color = WASD_COLOR
            canvas.drawCircle(px(last.x), py(last.y), 7f, p.fill)
            p.text.color = TEXT
            p.text.textSize = 18f
            canvas.drawText(
                String.format(Locale.US, "x %+.2f   y %+.2f", last.x, last.y),
                left,
                top + size + 30f,
                p.text
            )
        } else {
            p.text.color = MUTED
            p.text.textSize = 18f
            canvas.drawText("未记录到 WASD 输入", left, top + size + 30f, p.text)
        }

        p.text.color = MUTED
        p.text.textSize = 16f
        canvas.drawText("虚拟摇杆向量（非游戏内位置）", left, top + size + 56f, p.text)
    }

    private fun drawMousePanel(
        canvas: Canvas,
        p: Paints,
        pathPoints: List<com.devoid.keysync.domain.TrajectoryPoint>,
        tMs: Long,
    ) {
        val left = 584f
        val top = 120f
        val width = 632f
        val height = 480f

        p.fill.color = PANEL
        canvas.drawRoundRect(left, top, left + width, top + height, 20f, 20f, p.fill)

        p.text.textSize = 18f
        p.text.color = TEXT
        canvas.drawText("鼠标视角位移", left + 16f, top + 30f, p.text)

        val visible = pathPoints.filter { it.timeMs <= tMs }
        if (visible.size < 2) {
            p.text.color = MUTED
            p.text.textSize = 18f
            canvas.drawText("未记录到鼠标位移（未进入射击模式）", left + 16f, top + 70f, p.text)
            return
        }

        var minX = 0f
        var maxX = 0f
        var minY = 0f
        var maxY = 0f
        visible.forEach {
            minX = min(minX, it.x)
            maxX = max(maxX, it.x)
            minY = min(minY, it.y)
            maxY = max(maxY, it.y)
        }
        val spanX = max(maxX - minX, 120f)
        val spanY = max(maxY - minY, 120f)
        val pad = 28f
        val scale = min((width - pad * 2) / spanX, (height - pad * 2 - 30f) / spanY)
        val offsetX = left + width / 2f - (minX + maxX) / 2f * scale
        val offsetY = top + 30f + (height - 30f) / 2f - (minY + maxY) / 2f * scale

        fun sx(x: Float) = offsetX + x * scale
        fun sy(y: Float) = offsetY + y * scale

        val all = Path()
        visible.forEachIndexed { index, point ->
            if (index == 0) all.moveTo(sx(point.x), sy(point.y)) else all.lineTo(sx(point.x), sy(point.y))
        }
        p.line.color = MOUSE_COLOR
        p.line.alpha = 70
        canvas.drawPath(all, p.line)
        p.line.alpha = 255

        val recent = visible.filter { it.timeMs >= tMs - 2_000 }
        if (recent.size > 1) {
            val recentPath = Path()
            recent.forEachIndexed { index, point ->
                if (index == 0) recentPath.moveTo(sx(point.x), sy(point.y))
                else recentPath.lineTo(sx(point.x), sy(point.y))
            }
            canvas.drawPath(recentPath, p.line)
        }

        val start = visible.first()
        val end = visible.last()
        p.fill.color = OK_COLOR
        canvas.drawCircle(sx(start.x), sy(start.y), 6f, p.fill)
        p.fill.color = MOUSE_COLOR
        canvas.drawCircle(sx(end.x), sy(end.y), 8f, p.fill)

        p.text.color = TEXT
        p.text.textSize = 18f
        canvas.drawText(
            String.format(Locale.US, "总位移  Δx %+.0f   Δy %+.0f", end.x, end.y),
            left + 16f,
            top + height + 30f,
            p.text
        )
        p.text.color = MUTED
        p.text.textSize = 16f
        canvas.drawText("绿点起点 · 橙点当前位置", left + 16f, top + height + 56f, p.text)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (abs(ms) / 1000.0)
        return String.format(Locale.US, "%.1fs", totalSeconds)
    }
}
