package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LudoApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// Data structures
data class TokenState(
    val id: Int,
    val player: Int, // 0: Red, 1: Green, 2: Yellow, 3: Blue
    val steps: Int = -1 // -1 is Base, 0..50 is Path, 51..55 is Home Stretch, 56 is Home
)

data class GameState(
    val tokens: List<TokenState> = (0..3).flatMap { p -> (0..3).map { t -> TokenState(t, p) } },
    val currentPlayer: Int = 0,
    val diceValue: Int = 1,
    val hasRolled: Boolean = false,
    val message: String = "Red's turn. Roll the dice!",
    val winOrder: List<Int> = emptyList(),
    val lastKillTimestamp: Long = 0L,
    val killEventTimestamp: Long = 0L,
    val lastKillPos: Int = -1
)

// Board maps
val globalPath = listOf(
    Pair(13, 6), Pair(12, 6), Pair(11, 6), Pair(10, 6), Pair(9, 6),
    Pair(8, 5), Pair(8, 4), Pair(8, 3), Pair(8, 2), Pair(8, 1), Pair(8, 0),
    Pair(7, 0),
    Pair(6, 0), Pair(6, 1), Pair(6, 2), Pair(6, 3), Pair(6, 4), Pair(6, 5),
    Pair(5, 6), Pair(4, 6), Pair(3, 6), Pair(2, 6), Pair(1, 6), Pair(0, 6),
    Pair(0, 7),
    Pair(0, 8), Pair(1, 8), Pair(2, 8), Pair(3, 8), Pair(4, 8), Pair(5, 8),
    Pair(6, 9), Pair(6, 10), Pair(6, 11), Pair(6, 12), Pair(6, 13), Pair(6, 14),
    Pair(7, 14),
    Pair(8, 14), Pair(8, 13), Pair(8, 12), Pair(8, 11), Pair(8, 10), Pair(8, 9),
    Pair(9, 8), Pair(10, 8), Pair(11, 8), Pair(12, 8), Pair(13, 8), Pair(14, 8),
    Pair(14, 7),
    Pair(14, 6)
)

val safeCells = setOf(0, 8, 13, 21, 26, 34, 39, 47)

val basePositions = listOf(
    listOf(Pair(11f, 2f), Pair(11f, 4f), Pair(13f, 2f), Pair(13f, 4f)), // Red
    listOf(Pair(2f, 2f), Pair(2f, 4f), Pair(4f, 2f), Pair(4f, 4f)),     // Green
    listOf(Pair(2f, 11f), Pair(2f, 13f), Pair(4f, 11f), Pair(4f, 13f)), // Yellow
    listOf(Pair(11f, 11f), Pair(11f, 13f), Pair(13f, 11f), Pair(13f, 13f)) // Blue
)

val homeStretches = listOf(
    listOf(Pair(13, 7), Pair(12, 7), Pair(11, 7), Pair(10, 7), Pair(9, 7)), // Red
    listOf(Pair(7, 1), Pair(7, 2), Pair(7, 3), Pair(7, 4), Pair(7, 5)),     // Green
    listOf(Pair(1, 7), Pair(2, 7), Pair(3, 7), Pair(4, 7), Pair(5, 7)),     // Yellow
    listOf(Pair(7, 13), Pair(7, 12), Pair(7, 11), Pair(7, 10), Pair(7, 9))  // Blue
)

val homePositions = listOf(
    Pair(8.25f, 7.5f), // Red (Bottom triangle)
    Pair(7.5f, 6.75f), // Green (Left triangle)
    Pair(6.75f, 7.5f), // Yellow (Top triangle)
    Pair(7.5f, 8.25f)  // Blue (Right triangle)
)

fun tokenPos(token: TokenState): Pair<Float, Float> {
    return if (token.steps == -1) {
        basePositions[token.player][token.id]
    } else if (token.steps == 56) {
        homePositions[token.player]
    } else if (token.steps in 0..50) {
        val globalIdx = (token.player * 13 + token.steps) % 52
        val cell = globalPath[globalIdx]
        Pair(cell.first + 0.5f, cell.second + 0.5f)
    } else {
        val hsIdx = token.steps - 51
        val cell = homeStretches[token.player][hsIdx]
        Pair(cell.first + 0.5f, cell.second + 0.5f)
    }
}

class GameEngine {
    var state by mutableStateOf(GameState())
        private set

    fun processRoll(roll: Int, onDelay: (suspend () -> Unit) -> Unit) {
        if (state.hasRolled || state.winOrder.size >= 3) return
        
        val validMoves = getValidMoves(state.currentPlayer, roll)
        
        if (validMoves.isEmpty()) {
            state = state.copy(
                diceValue = roll,
                hasRolled = true,
                message = "${playerName(state.currentPlayer)} rolled $roll. No valid moves."
            )
            onDelay {
                kotlinx.coroutines.delay(1000)
                nextPlayer()
            }
        } else if (validMoves.size == 1) {
            state = state.copy(
                diceValue = roll,
                hasRolled = true,
                message = "${playerName(state.currentPlayer)} rolled $roll. Auto-moving token."
            )
            onDelay {
                kotlinx.coroutines.delay(800)
                moveToken(validMoves[0].id, validMoves[0].player, onDelay)
            }
        } else {
            state = state.copy(
                diceValue = roll,
                hasRolled = true,
                message = "${playerName(state.currentPlayer)} rolled $roll. Select a token to move."
            )
        }
    }

    private fun getValidMoves(playerIndex: Int, roll: Int): List<TokenState> {
        val playerTokens = state.tokens.filter { it.player == playerIndex }
        return playerTokens.filter { isValidMove(it, roll) }
    }

    fun isValidMove(token: TokenState, roll: Int): Boolean {
        if (token.steps == 56) return false
        if (token.steps == -1) return roll == 6
        if (token.steps + roll > 56) return false
        return true
    }

    fun moveToken(tokenId: Int, playerIndex: Int, launch: (suspend () -> Unit) -> Unit) {
        if (!state.hasRolled || state.currentPlayer != playerIndex || state.winOrder.size >= 3) return
        
        val token = state.tokens.find { it.id == tokenId && it.player == playerIndex } ?: return
        if (!isValidMove(token, state.diceValue)) return

        val originalSteps = token.steps
        val diceVal = state.diceValue
        
        val pathSteps = mutableListOf<Int>()
        if (originalSteps == -1) {
            pathSteps.add(0)
        } else {
            for (i in 1..diceVal) {
                pathSteps.add(originalSteps + i)
            }
        }
        
        val finalSteps = if (originalSteps == -1) 0 else originalSteps + diceVal

        launch {
            val movedTokens = state.tokens.toMutableList()
            val index = movedTokens.indexOfFirst { it.id == tokenId && it.player == playerIndex }
            movedTokens[index] = movedTokens[index].copy(steps = finalSteps)

            var killedOpponent = false
            var killGlobalPos = -1
            val opponentsToKill = mutableListOf<TokenState>()
            
            if (finalSteps in 0..50) {
                val globalPos = (playerIndex * 13 + finalSteps) % 52
                if (!safeCells.contains(globalPos)) {
                    val opponentsHere = movedTokens.filter {
                        it.player != playerIndex &&
                        it.steps in 0..50 &&
                        ((it.player * 13 + it.steps) % 52) == globalPos
                    }
                    if (opponentsHere.isNotEmpty()) {
                        opponentsToKill.addAll(opponentsHere)
                        killedOpponent = true
                        killGlobalPos = globalPos
                    }
                }
            }

            if (killedOpponent) {
                // 1. Move attacker
                state = state.copy(tokens = movedTokens)
                
                // 2. Wait for attacker to arrive at the cell
                kotlinx.coroutines.delay(400)
                
                // 3. Trigger zoom
                state = state.copy(
                    lastKillTimestamp = System.currentTimeMillis(),
                    lastKillPos = killGlobalPos
                )
                
                // 4. Wait for zoom peak (cinematic scale takes its time)
                kotlinx.coroutines.delay(800)
                
                // 5. Remove victims and trigger ring
                val killedTokens = state.tokens.toMutableList()
                opponentsToKill.forEach { opp ->
                    val oppIndex = killedTokens.indexOfFirst { it.id == opp.id && it.player == opp.player }
                    if (oppIndex != -1) {
                         killedTokens[oppIndex] = killedTokens[oppIndex].copy(steps = -1)
                    }
                }
                state = state.copy(tokens = killedTokens, killEventTimestamp = System.currentTimeMillis())
                
                // 6. Wait to show the effect before zoom out begins
                kotlinx.coroutines.delay(600)
            } else {
                state = state.copy(tokens = movedTokens)
            }

            val hasExtraTurn = state.diceValue == 6 || killedOpponent || finalSteps == 56

            val winOrder = state.winOrder.toMutableList()
            val finalTokens = state.tokens
            val isPlayerFinished = finalTokens.filter { it.player == playerIndex }.all { it.steps == 56 }
            if (isPlayerFinished && !winOrder.contains(playerIndex)) {
                winOrder.add(playerIndex)
            }

            state = state.copy(winOrder = winOrder)

            if (winOrder.size >= 3) {
                state = state.copy(message = "Game Over! Winner: ${playerName(winOrder[0])}", hasRolled = true)
            } else if (hasExtraTurn && !isPlayerFinished) {
                 state = state.copy(
                     hasRolled = false,
                     message = "Extra turn! Roll again."
                 )
            } else {
                 nextPlayer()
            }
        }
    }

    private fun nextPlayer() {
        var next = (state.currentPlayer + 1) % 4
        while (state.winOrder.contains(next) && state.winOrder.size < 3) {
            next = (next + 1) % 4
        }
        state = state.copy(
            currentPlayer = next,
            hasRolled = false,
            message = "${playerName(next)}'s turn. Roll the dice!"
        )
    }

    fun playerName(index: Int) = when(index) {
        0 -> "Red"
        1 -> "Green"
        2 -> "Yellow"
        3 -> "Blue"
        else -> "Unknown"
    }
}

val colorRed = Color(0xFFD50000)
val colorGreen = Color(0xFF00C853)
val colorYellow = Color(0xFFFFEA00)
val colorBlue = Color(0xFF2962FF)

fun getPlayerColor(player: Int) = when(player) {
    0 -> colorRed
    1 -> colorGreen
    2 -> colorYellow
    3 -> colorBlue
    else -> Color.Black
}
fun getPlayerLightColor(player: Int) = when(player) {
    0 -> Color(0xFFFF8A80)
    1 -> Color(0xFFB9F6CA)
    2 -> Color(0xFFFFFF8D)
    3 -> Color(0xFF82B1FF)
    else -> Color.LightGray
}

@Composable
fun DiceDots(value: Int) {
    Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        val dotRadius = size.width * 0.13f
        val padding = size.width * 0.18f
        
        fun drawDot(xStr: Float, yStr: Float) {
            val cx = if (xStr == 0f) size.width / 2 else if (xStr < 0) padding else size.width - padding
            val cy = if (yStr == 0f) size.height / 2 else if (yStr < 0) padding else size.height - padding
            drawCircle(color = Color.Black.copy(alpha=0.2f), radius = dotRadius * 1.1f, center = Offset(cx, cy + 3f))
            drawCircle(color = Color.White, radius = dotRadius, center = Offset(cx, cy))
            drawCircle(color = Color.Black.copy(alpha=0.1f), radius = dotRadius * 0.8f, center = Offset(cx, cy - 2f))
        }

        when (value) {
            1 -> { drawDot(0f, 0f) }
            2 -> { drawDot(-1f, -1f); drawDot(1f, 1f) }
            3 -> { drawDot(-1f, -1f); drawDot(0f, 0f); drawDot(1f, 1f) }
            4 -> { drawDot(-1f, -1f); drawDot(1f, 1f); drawDot(-1f, 1f); drawDot(1f, -1f) }
            5 -> { drawDot(-1f, -1f); drawDot(1f, 1f); drawDot(-1f, 1f); drawDot(1f, -1f); drawDot(0f, 0f) }
            6 -> { drawDot(-1f, -1f); drawDot(1f, 1f); drawDot(-1f, 1f); drawDot(1f, -1f); drawDot(-1f, 0f); drawDot(1f, 0f) }
        }
    }
}

@Composable
fun PlayerPanel(
    playerIdx: Int,
    name: String,
    isActive: Boolean,
    diceValue: Int,
    isRolling: Boolean,
    onRoll: (Offset, Int?) -> Unit
) {
    val color = getPlayerColor(playerIdx)
    val alpha by animateFloatAsState(if (isActive) 1f else 0.4f)
    val scale by animateFloatAsState(if (isActive) 1.05f else 1f)

    Row(
        modifier = Modifier
            .padding(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(2.dp, color, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier.size(40.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        
        Spacer(modifier = Modifier.width(8.dp))
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clickable { onRoll(Offset(0f, -10f), null) }
                    .graphicsLayer { shadowElevation = 8f; shape = RoundedCornerShape(12.dp) }
                    .background(color, RoundedCornerShape(12.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                DiceDots(if(isRolling) 0 else diceValue)
            }
        } else {
            Box(modifier = Modifier.size(45.dp)) // Placeholder to maintain size
        }
    }
}

@Composable
fun LudoApp(modifier: Modifier = Modifier) {
    val engine = remember { GameEngine() }
    val scope = rememberCoroutineScope()
    
    var diceVisible by remember { mutableStateOf(false) }
    var diceValue by remember { mutableStateOf(1) }
    var diceColor by remember { mutableStateOf(Color.Red) }
    val diceOffsetX = remember { Animatable(0f) }
    val diceOffsetY = remember { Animatable(0f) }
    val diceRotationX = remember { Animatable(0f) }
    val diceRotationY = remember { Animatable(0f) }
    val diceRotationZ = remember { Animatable(0f) }
    val diceScale = remember { Animatable(1f) }
    
    var isRolling by remember { mutableStateOf(false) }
    var showGoldenPulse by remember { mutableStateOf(false) }
    var currentDiceValue by remember { mutableStateOf(1) }

    val triggerRoll: (Offset, Int?) -> Unit = { dir, forcedValue ->
        if (!isRolling && !engine.state.hasRolled && engine.state.winOrder.size < 3) {
            val rollResult = forcedValue ?: Random.nextInt(1, 7)
            val pColor = getPlayerColor(engine.state.currentPlayer)
            
            diceColor = pColor
            isRolling = true
            showGoldenPulse = false
            diceValue = rollResult
            diceVisible = true

            scope.launch {
                val dist = sqrt(dir.x * dir.x + dir.y * dir.y)
                val nx = if (dist > 10f) dir.x / dist else 0f
                val ny = if (dist > 10f) dir.y / dist else -1f // default swipe up (throw from bottom)
                
                val launchDist = 1200f
                val startTransX = -nx * launchDist
                val startTransY = -ny * launchDist
                
                diceOffsetX.snapTo(startTransX)
                diceOffsetY.snapTo(startTransY)
                diceRotationX.snapTo(0f)
                diceRotationY.snapTo(0f)
                diceRotationZ.snapTo(0f)
                diceScale.snapTo(2.0f)
                
                val endX = (Random.nextFloat() - 0.5f) * 200f
                val endY = (Random.nextFloat() - 0.5f) * 200f
                
                val targetRots = when(rollResult) {
                    1 -> Triple(0f, 0f, 0f)
                    2 -> Triple(0f, -90f, 0f)
                    5 -> Triple(0f, 90f, 0f)
                    3 -> Triple(90f, 0f, 0f)
                    4 -> Triple(-90f, 0f, 0f)
                    6 -> Triple(180f, 0f, 0f)
                    else -> Triple(0f, 0f, 0f)
                }

                val rotX = 360f * 4f + targetRots.first
                val rotY = 360f * 3f + targetRots.second
                val rotZ = 360f * 2f + targetRots.third + (Random.nextFloat() * 60f - 30f)

                launch { diceOffsetX.animateTo(endX, animationSpec = spring(dampingRatio = 0.55f, stiffness = 60f)) }
                launch { diceOffsetY.animateTo(endY, animationSpec = spring(dampingRatio = 0.55f, stiffness = 60f)) }
                launch { diceRotationX.animateTo(rotX, animationSpec = tween(700, easing = FastOutSlowInEasing)) }
                launch { diceRotationY.animateTo(rotY, animationSpec = tween(700, easing = FastOutSlowInEasing)) }
                launch { diceRotationZ.animateTo(rotZ, animationSpec = tween(700, easing = FastOutSlowInEasing)) }
                launch {
                    diceScale.animateTo(1.3f, animationSpec = keyframes {
                        durationMillis = 700
                        2.0f at 0
                        1.0f at 400
                        1.4f at 550
                        1.3f at 700
                    })
                }
                
                delay(750)
                isRolling = false
                currentDiceValue = rollResult
                
                // Show golden pulse for 0.7 seconds
                showGoldenPulse = true
                delay(700)
                showGoldenPulse = false
                
                engine.processRoll(rollResult) { action -> scope.launch { action() } }
                
                delay(600)
                diceVisible = false
            }
        }
    }

    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE8EAF6))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, _ -> dragEnd = change.position },
                    onDragEnd = {
                        val dir = Offset(dragEnd.x - dragStart.x, dragEnd.y - dragStart.y)
                        val effDir = if (sqrt(dir.x * dir.x + dir.y * dir.y) < 20f) Offset(0f, 100f) else dir
                        triggerRoll(effDir, null)
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top UI: Green and Yellow
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(1, "Green", engine.state.currentPlayer == 1, currentDiceValue, isRolling, triggerRoll)
                }
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(2, "Yellow", engine.state.currentPlayer == 2, currentDiceValue, isRolling, triggerRoll)
                }
            }

            // Cinematic Zoom Animation
            var cinematicScale by remember { mutableStateOf(1f) }
            val animCinematicScale by animateFloatAsState(targetValue = cinematicScale, animationSpec = spring(dampingRatio = 0.5f, stiffness = 100f))
            
            LaunchedEffect(engine.state.lastKillTimestamp) {
                if (engine.state.lastKillTimestamp > 0) {
                    cinematicScale = 1.45f
                    delay(1500)
                    cinematicScale = 1f
                }
            }

            // The Board
            val killRingScale = remember { Animatable(0f) }
            val killRingAlpha = remember { Animatable(0f) }
            LaunchedEffect(engine.state.killEventTimestamp) {
                if (engine.state.killEventTimestamp > 0) {
                    killRingAlpha.snapTo(1f)
                    killRingScale.snapTo(0f)
                    launch { killRingScale.animateTo(1f, tween(800, easing = LinearOutSlowInEasing)) }
                    launch { killRingAlpha.animateTo(0f, tween(800)) }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = animCinematicScale
                        scaleY = animCinematicScale
                        if (engine.state.lastKillPos >= 0 && engine.state.lastKillPos < globalPath.size) {
                             val cell = globalPath[engine.state.lastKillPos]
                             val pivotX = (cell.second + 0.5f) / 15f
                             val pivotY = (cell.first + 0.5f) / 15f
                             transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
                        }
                    }
                    .background(Color.White)
                    .border(2.dp, Color.Black)
                    .drawWithContent {
                        drawContent()
                        if (killRingAlpha.value > 0f && engine.state.lastKillPos >= 0) {
                              val cell = globalPath[engine.state.lastKillPos]
                              val cx = (cell.second + 0.5f) * (this.size.width / 15f)
                              val cy = (cell.first + 0.5f) * (this.size.height / 15f)
                              drawCircle(
                                  color = Color.Red.copy(alpha = killRingAlpha.value),
                                  radius = (this.size.width / 30f) + (this.size.width / 5f) * killRingScale.value,
                                  center = Offset(cx, cy),
                                  style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f)
                              )
                        }
                    }
            ) {
                val cellPx = maxWidth / 15
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cxPx = size.width / 15f
                    
                    // Draw background bases
                    drawRect(color = getPlayerColor(0), topLeft = Offset(0f, 9 * cxPx), size = Size(6 * cxPx, 6 * cxPx))
                    drawRect(color = getPlayerColor(1), topLeft = Offset(0f, 0f), size = Size(6 * cxPx, 6 * cxPx))
                    drawRect(color = getPlayerColor(2), topLeft = Offset(9 * cxPx, 0f), size = Size(6 * cxPx, 6 * cxPx))
                    drawRect(color = getPlayerColor(3), topLeft = Offset(9 * cxPx, 9 * cxPx), size = Size(6 * cxPx, 6 * cxPx))
                    
                    // Draw Base white inner squares Center backgrounds
                    listOf(Offset(1f, 10f), Offset(1f, 1f), Offset(10f, 1f), Offset(10f, 10f)).forEach {
                        drawRect(color = Color.White, topLeft = Offset(it.x * cxPx, it.y * cxPx), size = Size(4 * cxPx, 4 * cxPx))
                        drawRect(color = Color.Black.copy(alpha = 0.5f), topLeft = Offset(it.x * cxPx, it.y * cxPx), size = Size(4 * cxPx, 4 * cxPx), style = Stroke(width = 4f))
                    }
                    
                    // Draw token resting circles in bases
                    basePositions.forEachIndexed { pIdx, positions ->
                        val color = getPlayerColor(pIdx)
                        positions.forEach {
                            drawCircle(color = color, radius = cxPx * 0.45f, center = Offset(it.second * cxPx, it.first * cxPx))
                            drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = cxPx * 0.45f, center = Offset(it.second * cxPx, it.first * cxPx), style = Stroke(width = 3f))
                            drawCircle(color = Color.White, radius = cxPx * 0.28f, center = Offset(it.second * cxPx, it.first * cxPx))
                        }
                    }
                    
                    // Draw Grid cells
                    for(row in 0..14) {
                        for(col in 0..14) {
                            val isPath = (row in 6..8) || (col in 6..8)
                            if (isPath && !(row in 6..8 && col in 6..8)) {
                                var cellColor = Color.White
                                val globalIdx = globalPath.indexOf(Pair(row, col))
                                if (globalIdx in safeCells) {
                                    cellColor = when (globalIdx) {
                                        0, 47 -> getPlayerColor(0)
                                        13, 8 -> getPlayerColor(1)
                                        26, 21 -> getPlayerColor(2)
                                        39, 34 -> getPlayerColor(3)
                                        else -> Color.LightGray
                                    }
                                } else {
                                    for(p in 0..3) {
                                        if (homeStretches[p].contains(Pair(row, col))) {
                                            cellColor = getPlayerColor(p)
                                        }
                                    }
                                }
                                drawRect(color = cellColor, topLeft = Offset(col * cxPx, row * cxPx), size = Size(cxPx, cxPx))
                                drawRect(color = Color.Black.copy(alpha = 0.8f), topLeft = Offset(col * cxPx, row * cxPx), size = Size(cxPx, cxPx), style = Stroke(width = 2f))
                                drawRect(color = Color.White.copy(alpha = 0.2f), topLeft = Offset(col * cxPx + 2f, row * cxPx + 2f), size = Size(cxPx - 4f, cxPx - 4f), style = Stroke(width = 2f))
                                
                                // Safe cell stars
                                if (globalIdx in safeCells) {
                                    val starPath = Path()
                                    val r = cxPx * 0.35f
                                    val cx = col * cxPx + cxPx/2
                                    val cy = row * cxPx + cxPx/2
                                    val innerR = r * 0.4f
                                    for (i in 0 until 10) {
                                        val rad = if (i % 2 == 0) r else innerR
                                        val a = Math.PI * i / 5.0 - Math.PI / 2.0
                                        val px = cx + rad * Math.cos(a).toFloat()
                                        val py = cy + rad * Math.sin(a).toFloat()
                                        if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
                                    }
                                    starPath.close()
                                    drawPath(starPath, Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }
                    }

                    // Draw center triangles
                    val centerX = 7.5f * cxPx
                    val centerY = 7.5f * cxPx
                    
                    val pathTop = Path().apply { moveTo(6 * cxPx, 6 * cxPx); lineTo(9 * cxPx, 6 * cxPx); lineTo(centerX, centerY); close() }
                    drawPath(pathTop, colorYellow)
                    
                    val pathRight = Path().apply { moveTo(9 * cxPx, 6 * cxPx); lineTo(9 * cxPx, 9 * cxPx); lineTo(centerX, centerY); close() }
                    drawPath(pathRight, colorBlue)
                    
                    val pathBottom = Path().apply { moveTo(6 * cxPx, 9 * cxPx); lineTo(9 * cxPx, 9 * cxPx); lineTo(centerX, centerY); close() }
                    drawPath(pathBottom, colorRed)
                    
                    val pathLeft = Path().apply { moveTo(6 * cxPx, 6 * cxPx); lineTo(6 * cxPx, 9 * cxPx); lineTo(centerX, centerY); close() }
                    drawPath(pathLeft, colorGreen)
                }

                // Draw tokens
                val tokenRadius = cellPx * 0.42f
                val groupedTokens = engine.state.tokens.groupBy { tokenPos(it) }

                val infiniteTransition = rememberInfiniteTransition()
                val activeRingRotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart)
                )
                val activePulse by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse)
                )

                engine.state.tokens.forEach { token ->
                    val pos = tokenPos(token)
                    val tokensAtPos = groupedTokens[pos]!!
                    val idxInGroup = tokensAtPos.indexOf(token)
                    
                    val isOnPath = token.steps in 0..50 || token.steps in 51..55
                    val shiftX = if (isOnPath && tokensAtPos.size > 1) 0.dp else 0.dp
                    val shiftY = if (isOnPath && tokensAtPos.size > 1) (idxInGroup * 6).dp else 0.dp

                    val targetX = cellPx * pos.second - tokenRadius + shiftX
                    val targetY = cellPx * pos.first - tokenRadius + shiftY
                    
                    
                    val animX by animateDpAsState(targetValue = targetX, animationSpec = spring(dampingRatio = 0.6f, stiffness = 60f))
                    val animY by animateDpAsState(targetValue = targetY, animationSpec = spring(dampingRatio = 0.6f, stiffness = 60f))
                    
                    
                    val isMyTurnToken = engine.state.currentPlayer == token.player && engine.state.hasRolled && !isRolling && engine.isValidMove(token, currentDiceValue)
                    
                    val scale by animateFloatAsState(
                         targetValue = if (isMyTurnToken) activePulse else if (isOnPath && tokensAtPos.size > 1) 0.85f else 1f,
                         animationSpec = spring()
                    )

                    val winPulseScale = remember { Animatable(1f) }
                    val winPulseAlpha = remember { Animatable(0f) }
                    LaunchedEffect(token.steps) {
                        if (token.steps == 56) {
                            winPulseScale.snapTo(1f)
                            winPulseAlpha.snapTo(1f)
                            launch { winPulseScale.animateTo(3f, tween(1200, easing = LinearOutSlowInEasing)) }
                            launch { winPulseAlpha.animateTo(0f, tween(1200)) }
                        }
                    }

                    Box(modifier = Modifier
                        .offset(x = animX, y = animY)
                        .size(tokenRadius * 2)
                        .zIndex(if (isOnPath) idxInGroup.toFloat() else 0f)
                        .drawBehind {
                            if (animX != targetX || animY != targetY) {
                                val dx = animX.toPx() - targetX.toPx()
                                val dy = animY.toPx() - targetY.toPx()
                                val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                                if (dist > 5f) {
                                    val tailLength = minOf(dist * 2.5f + size.width, 300f) 
                                    val nx = dx / dist
                                    val ny = dy / dist
                                    
                                    val currentSize = this.size.width
                                    val endTail = Offset(
                                        currentSize / 2f + nx * tailLength,
                                        currentSize / 2f + ny * tailLength
                                    )
                                    val centerOff = Offset(currentSize / 2f, currentSize / 2f)
                                    
                                    drawLine(
                                        color = Color(0xFF00E5FF),
                                        start = centerOff,
                                        end = endTail,
                                        strokeWidth = 24f,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        alpha = 0.85f
                                    )
                                }
                            }
                            if (winPulseAlpha.value > 0f) {
                                drawCircle(
                                    color = Color(0xFFFFD700).copy(alpha = winPulseAlpha.value),
                                    radius = size.width / 2f * winPulseScale.value,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f)
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = winPulseAlpha.value * 0.5f),
                                    radius = size.width / 2f * (winPulseScale.value * 1.3f),
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                                )
                            }
                        }
                    ) {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = if (animX != targetX || animY != targetY || isMyTurnToken) 24f else 8f
                                shape = CircleShape
                            }
                            .background(getPlayerColor(token.player), CircleShape)
                            .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
                            .clickable { 
                                engine.moveToken(token.id, token.player) { action -> scope.launch { action() } } 
                            }
                        ) {
                        if (isMyTurnToken) {
                            // Golden spinning ring
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = activeRingRotation }
                                .border(3.dp, Brush.sweepGradient(listOf(Color(0xFFFFD700), Color(0xFFFFF8DC), Color(0xFFFFD700))), CircleShape)
                            )
                        }
                        // Glossy plastic effect base gradient
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha=0.6f), Color.Transparent),
                                    center = Offset(tokenRadius.value * 0.8f, tokenRadius.value * 0.8f),
                                    radius = tokenRadius.value * 2f
                                ), CircleShape
                            )
                        )
                        // Inner indent line
                        Box(modifier = Modifier
                            .fillMaxSize(0.65f)
                            .align(Alignment.Center)
                            .border(1.5.dp, Color.Black.copy(alpha=0.25f), CircleShape)
                            .background(Color.Black.copy(alpha=0.1f), CircleShape)
                        )
                        // Inner ring core
                        Box(modifier = Modifier
                            .fillMaxSize(0.3f)
                            .align(Alignment.Center)
                            .background(Color.White.copy(alpha=0.3f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha=0.5f), CircleShape)
                        )
                        // Top specular highlight (oval)
                        Box(modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .fillMaxHeight(0.25f)
                            .offset(y = 2.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.White.copy(alpha=0.8f), Color.White.copy(alpha=0.1f))
                                ), 
                                shape = RoundedCornerShape(50)
                            )
                        )
                        } // End inner Box
                    }
                }
            }

            // Bottom UI: Red and Blue
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(0, "Red", engine.state.currentPlayer == 0, currentDiceValue, isRolling, triggerRoll)
                }
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(3, "Blue", engine.state.currentPlayer == 3, currentDiceValue, isRolling, triggerRoll)
                }
            }
            
            Column(
               modifier = Modifier.padding(bottom = 24.dp),
               horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var devClickCount by remember { mutableStateOf(0) }
                var showDevOptions by remember { mutableStateOf(false) }

                Text(
                    text = engine.state.message,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
                Text(
                    text = "Swipe anywhere or Tap active dice to roll",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp).clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        devClickCount++
                        if (devClickCount >= 7) {
                            showDevOptions = !showDevOptions
                            devClickCount = 0
                        }
                    }
                )
                if(engine.state.winOrder.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Rankings: " + engine.state.winOrder.joinToString(", ") { engine.playerName(it) },
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (showDevOptions) {
                    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..6).forEach { forceVal ->
                            androidx.compose.material3.Button(
                                onClick = { 
                                    triggerRoll(Offset(0f, -10f), forceVal)
                                },
                                modifier = Modifier.size(40.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text(forceVal.toString()) }
                        }
                    }
                }
            }
        }

        // Flying Dice Overlay
        if (diceVisible) {
            val commonModifier = Modifier
                .align(Alignment.Center)
                .offset(x = diceOffsetX.value.dp, y = diceOffsetY.value.dp)
                .size(90.dp)
                .graphicsLayer {
                    scaleX = diceScale.value
                    scaleY = diceScale.value
                    rotationZ = diceRotationZ.value
                    shadowElevation = if (isRolling) 32f else 16f
                    shape = RoundedCornerShape(16.dp)
                }

            Box(modifier = commonModifier, contentAlignment = Alignment.Center) {
                if (showGoldenPulse) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Restart)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(pulseScale)
                            .border(4.dp, Color(0xFFFFD700).copy(alpha = pulseAlpha), RoundedCornerShape(16.dp))
                    )
                }

                var visualValue by remember { mutableStateOf(diceValue) }
                LaunchedEffect(isRolling) {
                    if (isRolling) {
                        while (isRolling) {
                            visualValue = Random.nextInt(1, 7)
                            delay(40)
                        }
                    } else {
                        visualValue = diceValue
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(diceColor, RoundedCornerShape(16.dp))
                        .border(3.dp, Color.White, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    DiceDots(if (isRolling) visualValue else diceValue)
                }
            }
        }
    }
}

