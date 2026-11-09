package com.unitrading.chart.drawing

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

private fun SceneBuilder.zigzag() = polyline(pts)

/** A letter in a filled tag above a peak or below a trough; the neighbours of point [i] decide which. */
private fun SceneBuilder.pointTag(text: String, i: Int) {
    val here = pts.getOrNull(i) ?: return
    val before = pts.getOrNull(i - 1) ?: pts.getOrNull(i + 1) ?: here
    val after = pts.getOrNull(i + 1) ?: before
    val isPeak = here.y <= before.y && here.y <= after.y
    val trough = here.y >= before.y && here.y >= after.y
    val above = isPeak || !trough && here.y < (before.y + after.y) / 2
    label(text, Offset(here.x, here.y + (if (above) -7f else 7f) * px), color = ink, h = HAlign.CENTER, v = if (above) VAlign.BOTTOM else VAlign.TOP,
        sizeSp = textSize, bold = s.bold, italic = s.italic, background = base, padding = 3f * px, hit = false)
}

/** Dashed guide between two points with the ratio of two price legs at its middle. */
private fun SceneBuilder.ratioGuide(i: Int, j: Int, numerator: Pair<Int, Int>, denominator: Pair<Int, Int>) {
    if (pts.size <= maxOf(i, j)) return
    line(pts[i], pts[j], stroke.copy(width = px, dash = DrawingDash.DASHED), hit = false)
    val top = abs(d.points[numerator.second].price - d.points[numerator.first].price)
    val bottom = abs(d.points[denominator.second].price - d.points[denominator.first].price)
    if (bottom == 0.0) return
    label(formatRatio(top / bottom), mid(pts[i], pts[j]), color = ink, h = HAlign.CENTER, v = VAlign.MIDDLE, background = base, padding = 2f * px, hit = false)
}

internal fun SceneBuilder.harmonicPattern() {
    anchorHandles(); anchorMarks()
    if (pts.size >= 3) fillArea(pts.subList(0, 3), area, hit = true)
    if (pts.size >= 5) fillArea(pts.subList(2, 5), area, hit = true)
    zigzag()
    if (s.showLabels) "XABCD".forEachIndexed { i, letter -> pointTag(letter.toString(), i) }
    ratioGuide(0, 2, 1 to 2, 0 to 1)
    ratioGuide(1, 3, 2 to 3, 1 to 2)
    ratioGuide(2, 4, 3 to 4, 2 to 3)
    if (d.tool == DrawingTool.CYPHER) ratioGuide(0, 4, 3 to 4, 0 to 3) else ratioGuide(0, 4, 1 to 4, 0 to 1)
}

internal fun SceneBuilder.headAndShoulders() {
    anchorHandles(); anchorMarks()
    zigzag()
    if (pts.size >= 5) {
        val (left, right) = extendSegment(pts[2], pts[4], view, s.extendLeft, s.extendRight)
        line(left, right, stroke.copy(width = stroke.width, dash = DrawingDash.SOLID))
        if (area != null) fillArea(pts.subList(1, 3) + pts[2], area)
        fillArea(pts.subList(2, 5), area, hit = true)
    }
    if (pts.size >= 7) {
        fillArea(listOf(pts[0], pts[1], pts[2]), area, hit = true)
        fillArea(listOf(pts[4], pts[5], pts[6]), area, hit = true)
    }
    if (s.showLabels) listOf(1 to "左肩", 3 to "头部", 5 to "右肩").forEach { (i, text) -> pointTag(text, i) }
}

internal fun SceneBuilder.abcdPattern() {
    anchorHandles(); anchorMarks()
    zigzag()
    if (s.showLabels) "ABCD".forEachIndexed { i, letter -> pointTag(letter.toString(), i) }
    ratioGuide(0, 2, 1 to 2, 0 to 1)
    ratioGuide(1, 3, 2 to 3, 1 to 2)
}

internal fun SceneBuilder.trianglePattern() {
    anchorHandles(); anchorMarks()
    zigzag()
    if (pts.size >= 3) line(pts[0], pts[2], stroke.copy(width = px, dash = DrawingDash.DASHED), hit = false)
    if (pts.size >= 4) {
        line(pts[1], pts[3], stroke.copy(width = px, dash = DrawingDash.DASHED), hit = false)
        fillArea(listOf(pts[0], pts[1], pts[3], pts[2]), area, hit = true)
    }
    if (s.showLabels) "ABCD".forEachIndexed { i, letter -> pointTag(letter.toString(), i) }
}

internal fun SceneBuilder.threeDrives() {
    anchorHandles(); anchorMarks()
    zigzag()
    if (s.showLabels) listOf(1 to "1", 3 to "2", 5 to "3").forEach { (i, text) -> pointTag(text, i) }
    ratioGuide(1, 3, 2 to 3, 1 to 2)
    ratioGuide(3, 5, 4 to 5, 3 to 4)
    ratioGuide(2, 4, 3 to 4, 2 to 3)
    ratioGuide(4, 6, 5 to 6, 4 to 5)
}

private val elliottWaves = mapOf(
    DrawingTool.ELLIOTT_IMPULSE to listOf("0", "1", "2", "3", "4", "5"),
    DrawingTool.ELLIOTT_CORRECTION to listOf("0", "A", "B", "C"),
    DrawingTool.ELLIOTT_TRIANGLE to listOf("0", "A", "B", "C", "D", "E"),
    DrawingTool.ELLIOTT_DOUBLE to listOf("0", "W", "X", "Y"),
    DrawingTool.ELLIOTT_TRIPLE to listOf("0", "W", "X", "Y", "X", "Z"),
)

/** Wave [label] as written at a degree: 1 → I, (1), ① and so on. */
internal fun elliottLabel(label: String, style: Int): String {
    val digit = label.toIntOrNull()
    fun roman(value: Int) = listOf("I", "II", "III", "IV", "V").getOrElse(value - 1) { value.toString() }
    fun circled(text: String): String = when (text) {
        "1" -> "①"; "2" -> "②"; "3" -> "③"; "4" -> "④"; "5" -> "⑤"
        "A" -> "Ⓐ"; "B" -> "Ⓑ"; "C" -> "Ⓒ"; "D" -> "Ⓓ"; "E" -> "Ⓔ"
        "W" -> "Ⓦ"; "X" -> "Ⓧ"; "Y" -> "Ⓨ"; "Z" -> "Ⓩ"
        else -> text
    }
    return when (style) {
        0 -> "((${if (digit != null) roman(digit) else label}))"
        1 -> "(${if (digit != null) roman(digit) else label})"
        2 -> if (digit != null) roman(digit) else label
        3 -> circled(label)
        4 -> "($label)"
        5 -> label
        6 -> circled(label.lowercase()).let { if (digit != null) circled(label) else it }
        7 -> "(${if (digit != null) roman(digit).lowercase() else label.lowercase()})"
        else -> if (digit != null) roman(digit).lowercase() else label.lowercase()
    }
}

internal fun SceneBuilder.elliottWave() {
    anchorHandles(); anchorMarks()
    zigzag()
    if (!s.showLabels) return
    val style = ElliottDegree.entries.getOrElse(d.param("degree", 4.0).toInt()) { ElliottDegree.INTERMEDIATE }.style
    elliottWaves.getValue(d.tool).forEachIndexed { i, wave ->
        if (i == 0) return@forEachIndexed
        val here = pts.getOrNull(i) ?: return@forEachIndexed
        val before = pts[i - 1]
        val up = here.y < before.y
        label(elliottLabel(wave, style), Offset(here.x, here.y + (if (up) -6f else 6f) * px), color = base, h = HAlign.CENTER, v = if (up) VAlign.BOTTOM else VAlign.TOP, sizeSp = 14f, hit = false)
    }
}

internal fun SceneBuilder.cyclicLines() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val first = c.index(d.points[0].timeMs)
    val unit = c.index(d.points[1].timeMs) - first
    line(pts[0], pts[1], stroke.copy(width = stroke.width / 2, dash = DrawingDash.DASHED), hit = false)
    if (abs(unit) < 1e-6) return
    var k = 0
    while (k < 2000) {
        val x = c.xOfIndex(first + k * unit)
        if (x > plot.right + 2f || x < plot.left - 2f && unit < 0) break
        if (x >= plot.left - 2f) fullHeightLine(x)
        k++
    }
}

internal fun SceneBuilder.timeCycles() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val baseY = pts[0].y
    val diameter = pts[1].x - pts[0].x
    if (abs(diameter) < 1f) return
    val radius = abs(diameter) / 2
    var k = 0
    while (k < 500) {
        val left = pts[0].x + diameter * k + (if (diameter < 0) diameter else 0f)
        if (left > plot.right) break
        if (left + radius * 2 >= plot.left) {
            val arc = ellipsePoints(Offset(left + radius, baseY), radius, radius, from = PI, sweep = PI, steps = 64)
            fillArea(arc, area)
            polyline(arc)
        }
        k++
    }
}

internal fun SceneBuilder.sineLine() {
    anchorHandles(); anchorMarks()
    if (pts.size < 2) return
    val halfPeriod = pts[1].x - pts[0].x
    val amplitude = (pts[0].y - pts[1].y) / 2
    if (abs(halfPeriod) < .5f) { line(pts[0], pts[1]); return }
    val center = (pts[0].y + pts[1].y) / 2
    val points = ArrayList<Offset>()
    var x = view.left
    while (x <= view.right) {
        points += Offset(x, center + (amplitude * cos(PI * (x - pts[0].x) / halfPeriod)).toFloat())
        x += 2f * px
    }
    polyline(points)
}
