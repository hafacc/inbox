package cc.hafa.inbox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.R

private fun geist(weight: FontWeight) = Font(
    R.font.geist,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Geist, the app's one typeface, in the weights it uses. */
val Geist = FontFamily(
    geist(FontWeight.Normal),
    geist(FontWeight.Medium),
    geist(FontWeight.SemiBold),
    geist(FontWeight.Bold),
)

/** Geist Mono, for code. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight))),
)

private val base = Typography()

private fun TextStyle.geist() = copy(fontFamily = Geist)

/** latr's type scale in Geist, with the sizes the paper screens use. */
val InboxTypography = Typography(
    displayLarge = base.displayLarge.geist(),
    displayMedium = base.displayMedium.geist(),
    displaySmall = base.displaySmall.geist(),
    headlineLarge = base.headlineLarge.geist(),
    headlineMedium = base.headlineMedium.geist(),
    headlineSmall = base.headlineSmall.geist(),
    titleLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    titleMedium = base.titleMedium.geist().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = base.labelLarge.geist(),
    labelMedium = base.labelMedium.geist(),
    labelSmall = base.labelSmall.geist(),
)
