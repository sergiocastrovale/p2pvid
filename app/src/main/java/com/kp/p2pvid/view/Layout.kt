package com.kp.p2pvid.view

/** Integer rect in top-left origin coordinates. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

data class Tile(val party: Party, val box: Box, val isPip: Boolean = false)

/**
 * One layout function shared by the recorder; the on-screen Compose layout follows the
 * same rules. Tiles are drawn in list order (PiP last, on top). Each tile is center-cropped.
 *
 * - DYNAMIC / LOCKED: main party full canvas, other party as a PiP in the bottom-right corner.
 * - DUAL: host left, joiner right, each half of a 16:9 band. On a landscape canvas the band
 *   fills it; on a portrait canvas the band is letterboxed (centered vertically).
 */
object Layout {
    fun tiles(view: ViewState, canvasW: Int, canvasH: Int, showPip: Boolean = true): List<Tile> {
        if (view.mode == ViewMode.DUAL) {
            val bandH = if (canvasW >= canvasH) canvasH else canvasW * 9 / 16
            val top = (canvasH - bandH) / 2
            val half = canvasW / 2
            return listOf(
                Tile(Party.HOST, Box(0, top, half, bandH)),
                Tile(Party.JOINER, Box(half, top, canvasW - half, bandH)),
            )
        }
        val main = Tile(view.main, Box(0, 0, canvasW, canvasH))
        if (!showPip) return listOf(main)
        val short = minOf(canvasW, canvasH)
        val margin = short / 32
        // PiP keeps the canvas orientation: 1/4 of the short side wide in portrait.
        val pipW: Int
        val pipH: Int
        if (canvasW < canvasH) {
            pipW = short / 4; pipH = pipW * 16 / 9
        } else {
            pipH = short / 4; pipW = pipH * 16 / 9
        }
        val pip = Tile(view.main.other, Box(canvasW - pipW - margin, canvasH - pipH - margin, pipW, pipH), isPip = true)
        return listOf(main, pip)
    }

    /**
     * Texture scale for center-crop ("aspect fill") of a frame into a box.
     * Returns (sx, sy) <= 1: the fraction of the frame visible on each axis.
     */
    fun cropScale(frameW: Int, frameH: Int, boxW: Int, boxH: Int): Pair<Float, Float> {
        if (frameW <= 0 || frameH <= 0 || boxW <= 0 || boxH <= 0) return 1f to 1f
        val frameAspect = frameW.toFloat() / frameH
        val boxAspect = boxW.toFloat() / boxH
        return if (frameAspect > boxAspect) (boxAspect / frameAspect) to 1f else 1f to (frameAspect / boxAspect)
    }
}
