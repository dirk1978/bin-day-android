package uk.dirk1978.binday.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/**
 * Draws the wheelie bin (split into vertical stripes when several bins go out)
 * and the bin lorry, as bitmaps. Same shapes as the Chrome extension, laid out
 * on a 32-unit grid. Used by the screens and the notification's large icon.
 */
object BinArt {
  val COLOURS = mapOf(
    "Green" to 0xFF2E8B3D.toInt(),
    "Grey" to 0xFF6B7280.toInt(),
    "Brown" to 0xFF8B5A2B.toInt(),
  )
  private const val OUTLINE = 0xFF1F2937.toInt()
  private const val EMPTY = 0xFFD1D5DB.toInt()
  private const val VAN_BODY = 0xFFE5E7EB.toInt()
  private const val VAN_CAB = 0xFFF59E0B.toInt()
  private const val VAN_WINDOW = 0xFFBFDBFE.toInt()

  fun colourOf(bin: String): Int = COLOURS[bin] ?: EMPTY

  fun bin(size: Int, bins: List<String>): Bitmap = draw(size) { canvas, s ->
    val colours = bins.map(::colourOf).ifEmpty { listOf(EMPTY) }
    val body = Path().apply {
      moveTo(6 * s, 9 * s); lineTo(26 * s, 9 * s); lineTo(24 * s, 27 * s); lineTo(8 * s, 27 * s); close()
    }
    striped(canvas, body, colours, 6 * s, 20 * s)
    canvas.drawPath(body, stroke(s))

    val lid = Path().apply { addRect(RectF(4 * s, 5 * s, 28 * s, 9 * s), Path.Direction.CW) }
    striped(canvas, lid, colours, 4 * s, 24 * s)
    canvas.drawPath(lid, stroke(s))

    val handle = Path().apply { moveTo(12 * s, 5 * s); lineTo(12 * s, 3 * s); lineTo(20 * s, 3 * s); lineTo(20 * s, 5 * s) }
    canvas.drawPath(handle, stroke(s))
    for (x in listOf(10f, 22f)) canvas.drawCircle(x * s, 28.5f * s, 2.5f * s, fill(OUTLINE))
  }

  /** "The van's been." */
  fun van(size: Int): Bitmap = draw(size) { canvas, s ->
    if (size >= 32) {
      val lines = Path()
      for (y in listOf(12f, 17f, 22f)) { lines.moveTo(0.5f * s, y * s); lines.lineTo(3.5f * s, y * s) }
      canvas.drawPath(lines, stroke(s))
    }
    val body = RectF(5 * s, 8 * s, 21 * s, 24 * s)
    canvas.drawRect(body, fill(VAN_BODY))
    canvas.drawRect(body, stroke(s))
    val cab = Path().apply {
      moveTo(21 * s, 12 * s); lineTo(27 * s, 12 * s); lineTo(30 * s, 17 * s); lineTo(30 * s, 24 * s); lineTo(21 * s, 24 * s); close()
    }
    canvas.drawPath(cab, fill(VAN_CAB))
    canvas.drawPath(cab, stroke(s))
    val window = Path().apply {
      moveTo(23 * s, 14 * s); lineTo(26.5f * s, 14 * s); lineTo(28.5f * s, 17.5f * s); lineTo(23 * s, 17.5f * s); close()
    }
    canvas.drawPath(window, fill(VAN_WINDOW))
    for (x in listOf(10f, 25f)) canvas.drawCircle(x * s, 26 * s, 3 * s, fill(OUTLINE))
  }

  private inline fun draw(size: Int, block: (Canvas, Float) -> Unit): Bitmap =
    createBitmap(size, size).also { block(Canvas(it), size / 32f) }

  private fun striped(canvas: Canvas, path: Path, colours: List<Int>, x0: Float, width: Float) {
    val stripe = width / colours.size
    colours.forEachIndexed { i, colour ->
      canvas.save()
      // Overlap by half a pixel so no seam shows between stripes.
      canvas.clipRect(x0 + i * stripe, 0f, x0 + (i + 1) * stripe + if (i < colours.lastIndex) 0.5f else 0f, canvas.height.toFloat())
      canvas.drawPath(path, fill(colour))
      canvas.restore()
    }
  }

  private fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; style = Paint.Style.FILL }

  private fun stroke(s: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = OUTLINE
    style = Paint.Style.STROKE
    strokeWidth = maxOf(1f, 1.5f * s)
    strokeJoin = Paint.Join.ROUND
  }
}
