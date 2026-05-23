package com.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun rotate(rx: Float, ry: Float, rz: Float): Vec3 {
        // X rotation
        val x1 = x
        val y1 = y * cos(rx) - z * sin(rx)
        val z1 = y * sin(rx) + z * cos(rx)
        // Y rotation
        val x2 = x1 * cos(ry) + z1 * sin(ry)
        val y2 = y1
        val z2 = -x1 * sin(ry) + z1 * cos(ry)
        // Z rotation
        val x3 = x2 * cos(rz) - y2 * sin(rz)
        val y3 = x2 * sin(rz) + y2 * cos(rz)
        val z3 = z2
        return Vec3(x3, y3, z3)
    }
}

class FaceDef(
    val id: Int, 
    val normal: Vec3, 
    val corners: List<Vec3>,
    val dotMapper: (Float, Float) -> Vec3
)

val diceFaces = listOf(
    FaceDef(
        id = 1,
        normal = Vec3(0f, 0f, 1f),
        corners = listOf(Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f)),
        dotMapper = { u, v -> Vec3(u, v, 1f) }
    ),
    FaceDef(
        id = 6,
        normal = Vec3(0f, 0f, -1f),
        corners = listOf(Vec3(1f, -1f, -1f), Vec3(-1f, -1f, -1f), Vec3(-1f, 1f, -1f), Vec3(1f, 1f, -1f)),
        dotMapper = { u, v -> Vec3(-u, v, -1f) }
    ),
    FaceDef(
        id = 2,
        normal = Vec3(1f, 0f, 0f),
        corners = listOf(Vec3(1f, -1f, 1f), Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(1f, 1f, 1f)),
        dotMapper = { u, v -> Vec3(1f, v, -u) }
    ),
    FaceDef(
        id = 5,
        normal = Vec3(-1f, 0f, 0f),
        corners = listOf(Vec3(-1f, -1f, -1f), Vec3(-1f, -1f, 1f), Vec3(-1f, 1f, 1f), Vec3(-1f, 1f, -1f)),
        dotMapper = { u, v -> Vec3(-1f, v, u) }
    ),
    FaceDef(
        id = 3,
        normal = Vec3(0f, 1f, 0f),
        corners = listOf(Vec3(-1f, 1f, 1f), Vec3(1f, 1f, 1f), Vec3(1f, 1f, -1f), Vec3(-1f, 1f, -1f)),
        dotMapper = { u, v -> Vec3(u, 1f, -v) }
    ),
    FaceDef(
        id = 4,
        normal = Vec3(0f, -1f, 0f),
        corners = listOf(Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, -1f, 1f), Vec3(-1f, -1f, 1f)),
        dotMapper = { u, v -> Vec3(u, -1f, v) }
    )
)

fun getFaceDots(value: Int): List<Pair<Float, Float>> {
    return when(value) {
        1 -> listOf(0f to 0f)
        2 -> listOf(-0.5f to -0.5f, 0.5f to 0.5f)
        3 -> listOf(-0.5f to -0.5f, 0f to 0f, 0.5f to 0.5f)
        4 -> listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f)
        5 -> listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f, 0f to 0f)
        6 -> listOf(-0.5f to -0.6f, -0.5f to 0f, -0.5f to 0.6f, 0.5f to -0.6f, 0.5f to 0f, 0.5f to 0.6f)
        else -> emptyList()
    }
}

@Composable
fun Dice3DCustom(
    modifier: Modifier = Modifier,
    rx: Float,
    ry: Float,
    rz: Float,
    baseColor: Color,
    dotColor: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val radX = java.lang.Math.toRadians(rx.toDouble()).toFloat()
        val radY = java.lang.Math.toRadians(ry.toDouble()).toFloat()
        val radZ = java.lang.Math.toRadians(rz.toDouble()).toFloat()

        val scale = size.minDimension / 2.0f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Draw background shaded faces
        diceFaces.forEach { face ->
            val rotatedNormal = face.normal.rotate(radX, radY, radZ)
            if (rotatedNormal.z > 0) { // Facing camera
                val corners2D = face.corners.map { c ->
                    val rc = c.rotate(radX, radY, radZ)
                    Offset(center.x + rc.x * scale, center.y + rc.y * scale)
                }

                val path = Path().apply {
                    moveTo(corners2D[0].x, corners2D[0].y)
                    lineTo(corners2D[1].x, corners2D[1].y)
                    lineTo(corners2D[2].x, corners2D[2].y)
                    lineTo(corners2D[3].x, corners2D[3].y)
                    close()
                }

                // Shading based on normal
                val brightness = 0.6f + 0.4f * rotatedNormal.z
                val shadedColor = baseColor.copy(
                    red = (baseColor.red * brightness).coerceIn(0f, 1f),
                    green = (baseColor.green * brightness).coerceIn(0f, 1f),
                    blue = (baseColor.blue * brightness).coerceIn(0f, 1f)
                )

                drawPath(path, shadedColor, style = Fill)
                drawPath(path, Color.Black.copy(alpha=0.2f), style = Stroke(width = 4f))

                // Draw dots for the face
                // To support 'diceValue', we could map faces to specific numbers.
                // Normally opposite faces sum to 7. 1 is Z=1, 6 is Z=-1.
                // 2 is X=1, 5 is X=-1. 3 is Y=1, 4 is Y=-1.
                val dotSize = scale * 0.26f
                val dots = getFaceDots(face.id)
                dots.forEach { (u, v) ->
                    val dot3D = face.dotMapper(u, v)
                    val rotatedDot = dot3D.rotate(radX, radY, radZ)
                    val dot2D = Offset(center.x + rotatedDot.x * scale, center.y + rotatedDot.y * scale)
                    
                    // Simple perspective for dot size
                    val zScale = 1f + (rotatedDot.z * 0.1f)
                    
                    val ellipseWidth = dotSize * zScale
                    val ellipseHeight = dotSize * zScale
                    
                    drawCircle(
                        color = dotColor,
                        radius = (ellipseWidth + ellipseHeight)/2f,
                        center = dot2D
                    )
                }
            }
        }
    }
}
