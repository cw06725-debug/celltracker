package com.example.celltracker

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val violet=Color(0xFFA46CD6)
private val blue=Color(0xFF6F7EDB)
private val mint=Color(0xFF62CEC6)

/** Stable shader brushes — no continuously animated gradients on the telephony refresh path. */
@Composable
internal fun gradientAccentBrushV130(primary: Boolean = true): Brush {
    val dark=isSystemInDarkTheme()
    return remember(primary,dark) {
        if(primary) Brush.horizontalGradient(if(dark) listOf(Color(0xFF7752B6),Color(0xFF5685B8),Color(0xFF368D86))
            else listOf(violet,blue,mint))
        else Brush.horizontalGradient(if(dark) listOf(Color(0xFF413451),Color(0xFF304551),Color(0xFF29514D))
            else listOf(Color(0xFFF1E4FB),Color(0xFFE9EEFE),Color(0xFFDFF7ED)))
    }
}

@Composable
internal fun gradientCardBrushV130(tint: Color? = null): Brush {
    val dark=isSystemInDarkTheme()
    return remember(tint,dark) {
        if (dark) Brush.linearGradient(listOf(Color(0xFF373047), Color(0xFF2D3545),Color(0xFF273E3E)))
        else if (tint!=null) {
            fun mix(a: Color,b:Color,f:Float)=Color(a.red*(1-f)+b.red*f,a.green*(1-f)+b.green*f,a.blue*(1-f)+b.blue*f,1f)
            Brush.linearGradient(listOf(mix(Color.White,tint,.14f), mix(Color(0xFFF4EDFC),tint,.07f), mix(Color(0xFFE2F8F1),tint,.09f)))
        } else Brush.linearGradient(listOf(Color(0xFFF0E7FB),Color(0xFFF7F0FA),Color(0xFFE4F7F1)))
    }
}

@Composable
internal fun GradientActionV130(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = true, enabled: Boolean = true
) {
    val shape=RoundedCornerShape(22.dp)
    val brush=gradientAccentBrushV130(primary)
    Button(
        onClick=onClick, enabled=enabled,
        modifier=modifier.clip(shape).background(if(enabled) brush else gradientAccentBrushV130(false)),
        shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,
            contentColor=if(primary)Color.White else MaterialTheme.colorScheme.primary,
            disabledContainerColor=Color.Transparent,disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant),
        border=if(primary) null else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=.12f)),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=15.dp)
    ) { Text(title,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold) }
}

/** Shared report actions: keep existing onClick/enabled logic, unify surfaces and text.
 * Use the pale gradient for secondary/dangerous actions, high contrast for primary.
 */
@Composable
internal fun ReportActionV130(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val brush = if (enabled) gradientAccentBrushV130(true) else gradientAccentBrushV130(false)
    Button(
        onClick=onClick, enabled=enabled,
        modifier=modifier.clip(shape).background(brush), shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent, contentColor=Color.White,
            disabledContainerColor=Color.Transparent, disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=13.dp), content=content
    )
}

@Composable
internal fun ReportOutlineActionV130(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val shape=RoundedCornerShape(20.dp)
    Button(
        onClick=onClick, enabled=enabled,
        modifier=modifier.clip(shape).background(gradientAccentBrushV130(false)), shape=shape,
        colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,
            contentColor=MaterialTheme.colorScheme.primary, disabledContainerColor=Color.Transparent,
            disabledContentColor=MaterialTheme.colorScheme.onSurfaceVariant),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.14f)),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=13.dp), content=content
    )
}
