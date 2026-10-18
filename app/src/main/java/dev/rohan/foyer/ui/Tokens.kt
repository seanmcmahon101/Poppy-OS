package dev.rohan.foyer.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens. 960 x 540 dp canvas, 1 dp = 2 px on the TV. */
object Ink {
    val Paper = Color(0xFFF5F7FA)
    val Mist = Paper.copy(alpha = 0.64f)
    val Whisper = Paper.copy(alpha = 0.50f)
    val Shadow = Color(0xFF05070B)
    val Ember = Color(0xFFFFD9B3)
    val Amber = Color(0xFFFFB020)
}

object Dims {
    val Margin = 56.dp
    val ClockTop = 38.dp

    val ButtonSize = 40.dp
    val ButtonGap = 12.dp
    val ButtonTop = 42.dp
    val Glyph = 22.dp

    val TileW = 152.dp
    val TileH = 85.5.dp
    val TileGap = 17.dp
    val TileRadius = 11.dp
    val ShelfBottom = 75.dp // from screen bottom to the tiles' bottom edge

    val SheetH = 260.dp
    val CustomizeH = 340.dp
    /** 6 columns spanning the full 56..904 dp line: 6 x 128 + 5 x 16 = 848. */
    val MiniW = 128.dp
    val MiniH = 72.dp
    val MiniGapX = 16.dp
    val MiniGapY = 14.dp
    val MiniRadius = 9.dp
}

object Motion {
    /** Tile focus, slight overshoot: cubic-bezier(.2,.9,.25,1.15). */
    val Lift = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.15f)
    /** Glides (label, shelf scroll, sheets): cubic-bezier(.2,.8,.2,1). */
    val Glide = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

private val textShadow = Shadow(color = Color.Black.copy(alpha = 0.35f), offset = Offset(0f, 2f), blurRadius = 24f)

object Type {
    /** Instrument Serif at 88 sp: Rohan's mockup (148 px = 74 sp), a bit bigger. Line height .9, tracking -.01 em. */
    val Clock = TextStyle(
        fontFamily = InstrumentSerif, fontWeight = FontWeight.Normal, fontSize = 88.sp, lineHeight = 80.sp,
        letterSpacing = (-0.9).sp, color = Ink.Paper, shadow = textShadow,
    )
    val Date = TextStyle(
        fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp,
        color = Ink.Mist, shadow = textShadow,
    )
    val DevTag = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Ink.Amber)
    val Label = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = Ink.Paper, shadow = textShadow)
    val Caption = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, color = Ink.Whisper)
    val SheetTitle = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = Ink.Mist)
    val Row = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = Ink.Paper)
    val RowNote = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Ink.Whisper)
    val ButtonLabel = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, color = Ink.Paper, shadow = textShadow)
    val TileName = TextStyle(fontFamily = GoogleSansFlex, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, color = Ink.Paper)
}
