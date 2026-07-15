package com.hdrstacker.panorama

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as GRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Handle { TL, TR, BL, BR, MOVE, NONE }

/**
 * Full-screen crop editor over the stitched [bitmap]. The user drags the four
 * corner handles to resize or the interior to move; [onSave] receives the crop
 * rectangle in bitmap pixel coordinates, [onDiscard] throws the result away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    bitmap: Bitmap,
    busy: Boolean,
    onSave: (Rect) -> Unit,
    onDiscard: () -> Unit,
) {
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    // Crop rectangle in image-pixel coordinates.
    var crop by remember(bitmap) { mutableStateOf(GRect(0f, 0f, imgW, imgH)) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val handleR = with(LocalDensity.current) { 22.dp.toPx() }
    val minCrop = 16f

    Scaffold(
        topBar = { TopAppBar(title = { Text("Crop panorama") }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                "Drag the corners to crop, or drag the middle to move. " +
                    "Trim the ragged edges, then save.",
                modifier = Modifier.padding(16.dp),
            )
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                    canvas = Size(it.width.toFloat(), it.height.toFloat())
                },
            ) {
                if (canvas != Size.Zero) {
                    val scale = min(canvas.width / imgW, canvas.height / imgH)
                    val dispW = imgW * scale
                    val dispH = imgH * scale
                    val offX = (canvas.width - dispW) / 2f
                    val offY = (canvas.height - dispH) / 2f

                    fun toScreen(x: Float, y: Float) = Offset(offX + x * scale, offY + y * scale)

                    Canvas(
                        Modifier.fillMaxSize().pointerInput(bitmap, scale) {
                            var grabbed = Handle.NONE
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val tl = toScreen(crop.left, crop.top)
                                    val tr = toScreen(crop.right, crop.top)
                                    val bl = toScreen(crop.left, crop.bottom)
                                    val br = toScreen(crop.right, crop.bottom)
                                    grabbed = when {
                                        (pos - tl).getDistance() <= handleR -> Handle.TL
                                        (pos - tr).getDistance() <= handleR -> Handle.TR
                                        (pos - bl).getDistance() <= handleR -> Handle.BL
                                        (pos - br).getDistance() <= handleR -> Handle.BR
                                        pos.x in tl.x..tr.x && pos.y in tl.y..bl.y -> Handle.MOVE
                                        else -> Handle.NONE
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val dx = drag.x / scale
                                    val dy = drag.y / scale
                                    crop = when (grabbed) {
                                        Handle.TL -> GRect(
                                            (crop.left + dx).coerceIn(0f, crop.right - minCrop),
                                            (crop.top + dy).coerceIn(0f, crop.bottom - minCrop),
                                            crop.right, crop.bottom,
                                        )
                                        Handle.TR -> GRect(
                                            crop.left,
                                            (crop.top + dy).coerceIn(0f, crop.bottom - minCrop),
                                            (crop.right + dx).coerceIn(crop.left + minCrop, imgW),
                                            crop.bottom,
                                        )
                                        Handle.BL -> GRect(
                                            (crop.left + dx).coerceIn(0f, crop.right - minCrop),
                                            crop.top,
                                            crop.right,
                                            (crop.bottom + dy).coerceIn(crop.top + minCrop, imgH),
                                        )
                                        Handle.BR -> GRect(
                                            crop.left, crop.top,
                                            (crop.right + dx).coerceIn(crop.left + minCrop, imgW),
                                            (crop.bottom + dy).coerceIn(crop.top + minCrop, imgH),
                                        )
                                        Handle.MOVE -> {
                                            val w = crop.width
                                            val h = crop.height
                                            val nl = (crop.left + dx).coerceIn(0f, imgW - w)
                                            val nt = (crop.top + dy).coerceIn(0f, imgH - h)
                                            GRect(nl, nt, nl + w, nt + h)
                                        }
                                        Handle.NONE -> crop
                                    }
                                },
                            )
                        },
                    ) {
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                            dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                        )
                        drawCropOverlay(
                            tl = toScreen(crop.left, crop.top),
                            br = toScreen(crop.right, crop.bottom),
                            canvasSize = canvas,
                            handleR = handleR,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDiscard, enabled = !busy) { Text("Discard") }
                Button(
                    onClick = {
                        val r = Rect(
                            crop.left.roundToInt().coerceIn(0, bitmap.width),
                            crop.top.roundToInt().coerceIn(0, bitmap.height),
                            crop.right.roundToInt().coerceIn(0, bitmap.width),
                            crop.bottom.roundToInt().coerceIn(0, bitmap.height),
                        )
                        onSave(r)
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy) "Saving…" else "Save crop")
                }
            }
        }
    }
}

private fun DrawScope.drawCropOverlay(
    tl: Offset,
    br: Offset,
    canvasSize: Size,
    handleR: Float,
) {
    val dim = Color(0x99000000)
    // Dim the four regions outside the crop rectangle.
    drawRect(dim, topLeft = Offset(0f, 0f), size = Size(canvasSize.width, tl.y))
    drawRect(dim, topLeft = Offset(0f, br.y), size = Size(canvasSize.width, canvasSize.height - br.y))
    drawRect(dim, topLeft = Offset(0f, tl.y), size = Size(tl.x, br.y - tl.y))
    drawRect(dim, topLeft = Offset(br.x, tl.y), size = Size(canvasSize.width - br.x, br.y - tl.y))

    val border = Color.White
    drawRect(
        color = border,
        topLeft = tl,
        size = Size(br.x - tl.x, br.y - tl.y),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
    )
    for (c in listOf(tl, Offset(br.x, tl.y), Offset(tl.x, br.y), br)) {
        drawCircle(border, radius = handleR / 2f, center = c)
    }
}
