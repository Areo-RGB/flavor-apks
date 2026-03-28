package com.paul.sprintsync.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.paul.sprintsync.R

val Typography = Typography()

val TabularMonospaceTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium,
)

val InterFontFamily = FontFamily(
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

val InterExtraBoldTabularTypography = TextStyle(
    fontFamily = InterFontFamily,
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.ExtraBold,
)
