package app.soundfield.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Палитра из прототипа «Хороший минимализм». */
object SFColors {
    val BgTop = Color(0xFF16213E)
    val BgBottom = Color(0xFF0F3460)
    val Accent1 = Color(0xFF667EEA)
    val Accent2 = Color(0xFF764BA2)
    val Text = Color(0xFFE2E8F0)
    val TextDim = Color(0xFF94A3B8)
    val Card = Color(0x0DFFFFFF)          // white 5 %
    val CardBorder = Color(0x1AFFFFFF)    // white 10 %
    val Secondary = Color(0x1AFFFFFF)
    val SecondaryBorder = Color(0x33FFFFFF)
    val Left = Color(0xFF3B82F6)
    val LeftText = Color(0xFF60A5FA)
    val Right = Color(0xFFEF4444)
    val RightText = Color(0xFFF87171)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFEF4444)
    val Master = Color(0xFFC084FC)

    val background = Brush.verticalGradient(listOf(BgTop, BgBottom))
    val accent = Brush.linearGradient(listOf(Accent1, Accent2))

    fun channel(ch: Int) = if (ch == 0) Left else Right
    fun channelText(ch: Int) = if (ch == 0) LeftText else RightText
}

@Composable
fun SoundFieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SFColors.Accent1,
            secondary = SFColors.Accent2,
            background = SFColors.BgTop,
            surface = Color(0xFF1B2A4E),
            onSurface = SFColors.Text,
            onBackground = SFColors.Text,
        ),
        content = content,
    )
}
