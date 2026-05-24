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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.withTransform
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

enum class PlayerMode { HUMAN, COMPUTER, INACTIVE }
enum class GameMode { CLASSIC, QUICK, TEAM }
enum class ScreenState { MENU, PLAYING }

// Data structures
data class TokenState(
    val id: Int,
    val player: Int, // 0: Red, 1: Green, 2: Yellow, 3: Blue
    val steps: Int = -1, // -1 is Base, 0..50 is Path, 51..55 is Home Stretch, 56 is Home
    val rivalPlayer: Int = -1
)

data class TrailState(
    val playerIndex: Int = -1,
    val path: List<Int> = emptyList(), // global positions
    val timestamp: Long = 0L,
    val isFadingOut: Boolean = false
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
    val lastKillPos: Int = -1,
    val lastKillerId: Int = -1,
    val isRevengeEvent: Boolean = false,
    val trailState: TrailState = TrailState(),
    val playerModes: Map<Int, PlayerMode> = mapOf(
        0 to PlayerMode.HUMAN,
        1 to PlayerMode.HUMAN,
        2 to PlayerMode.HUMAN,
        3 to PlayerMode.HUMAN
    ),
    val gameMode: GameMode = GameMode.CLASSIC
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

class SoundManager(context: android.content.Context) {

    private fun playBeep(freq: Double, durationMs: Int) {
        Thread {
            try {
                val sampleRate = 44100
                val numSamples = durationMs * sampleRate / 1000
                val sample = DoubleArray(numSamples)
                val generatedSnd = ByteArray(2 * numSamples)
                
                for (i in 0 until numSamples) {
                    sample[i] = kotlin.math.sin(2.0 * Math.PI * i / (sampleRate / freq))
                }
                var idx = 0
                for (dVal in sample) {
                    val valShort = (dVal * 32767).toInt().toShort()
                    generatedSnd[idx++] = (valShort.toInt() and 0x00ff).toByte()
                    generatedSnd[idx++] = ((valShort.toInt() and 0xff00).ushr(8)).toByte()
                }
                
                val audioTrack = android.media.AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, generatedSnd.size,
                    android.media.AudioTrack.MODE_STATIC
                )
                audioTrack.write(generatedSnd, 0, generatedSnd.size)
                audioTrack.play()
                Thread.sleep(durationMs.toLong() + 50)
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore
            }
        }.start()
    }

    fun playDiceRoll() {
        playBeep(440.0, 100)
        playBeep(660.0, 100)
    }

    fun playTokenHop() {
        playBeep(880.0, 80)
    }

    fun playKillStrike() {
        playBeep(220.0, 300)
        playBeep(110.0, 400)
    }
    
    fun playWinSequence() {
        playBeep(523.25, 200) // C5
        playBeep(659.25, 200) // E5
        playBeep(783.99, 200) // G5
        playBeep(1046.50, 400) // C6
    }

    fun playButtonClick() {
        playBeep(1200.0, 50)
    }

    fun release() {
        // No-op for synth
    }
}

class GameEngine(
    private val playSound: (String) -> Unit = {},
    var onRequestRoll: () -> Unit = {}
) {
    var state by mutableStateOf(GameState())
        private set

    fun initGame(modes: Map<Int, PlayerMode>, gMode: GameMode) {
        val initialTokens = modes.filter { it.value != PlayerMode.INACTIVE }
            .keys.flatMap { p -> (0..3).map { t -> TokenState(t, p) } }
            
        val firstPlayer = modes.entries.firstOrNull { it.value != PlayerMode.INACTIVE }?.key ?: 0
        state = GameState(
            tokens = initialTokens,
            currentPlayer = firstPlayer,
            message = "${playerName(firstPlayer)}'s turn. Roll the dice!",
            playerModes = modes,
            gameMode = gMode
        )
    }

    fun isGameOver(): Boolean {
        return if (state.gameMode == GameMode.TEAM) {
            (state.winOrder.contains(0) && state.winOrder.contains(2)) || (state.winOrder.contains(1) && state.winOrder.contains(3))
        } else {
            val activePlayers = state.playerModes.count { it.value != PlayerMode.INACTIVE }
            state.winOrder.size >= activePlayers - 1
        }
    }

    fun processRoll(roll: Int, onDelay: (suspend () -> Unit) -> Unit) {
        if (state.hasRolled || isGameOver()) return
        
        state = state.copy(
            diceValue = roll,
            hasRolled = true
        )
        
        checkAutoPlayCondition(onDelay)
    }

    fun checkAutoPlayCondition(onDelay: (suspend () -> Unit) -> Unit) {
        val validMoves = getValidMoves(state.currentPlayer, state.diceValue)
        val isAI = state.playerModes[state.currentPlayer] == PlayerMode.COMPUTER
        
        if (validMoves.isEmpty()) {
            state = state.copy(
                message = "${playerName(state.currentPlayer)} rolled ${state.diceValue}. No valid moves."
            )
            onDelay {
                kotlinx.coroutines.delay(1000)
                nextPlayer()
                triggerAIIfNecessary(onDelay)
            }
        } else if (isAI) {
            val bestMove = decideBestMoveForAI(validMoves)
            onDelay {
                kotlinx.coroutines.delay(1000) // Small pause so we can see the AI think
                moveToken(bestMove.id, bestMove.player, onDelay)
            }
        } else if (validMoves.size == 1) {
            state = state.copy(
                message = "${playerName(state.currentPlayer)} rolled ${state.diceValue}. Auto-moving token."
            )
            onDelay {
                moveToken(validMoves[0].id, validMoves[0].player, onDelay)
            }
        } else {
            state = state.copy(
                message = "${playerName(state.currentPlayer)} rolled ${state.diceValue}. Select a token to move."
            )
        }
    }

    fun getBestMove(): TokenState? {
        val validMoves = state.tokens.filter { it.player == state.currentPlayer && isValidMove(it, state.diceValue) }
        return if (validMoves.isNotEmpty()) decideBestMoveForAI(validMoves) else null
    }

    private fun decideBestMoveForAI(validMoves: List<TokenState>): TokenState {
        val roll = state.diceValue
        var bestMove: TokenState? = null
        var bestScore = -1
        
        // Helper to check if a global position is vulnerable to enemies
        fun isVulnerableAt(globalPos: Int, playerIdx: Int): Boolean {
            if (safeCells.contains(globalPos)) return false
            val enemies = state.tokens.filter { 
                it.player != playerIdx && 
                !(state.gameMode == GameMode.TEAM && it.player == (playerIdx + 2) % 4) &&
                it.steps in 0..50 
            }
            for (e in enemies) {
                val eGlobal = (e.player * 13 + e.steps) % 52
                val dist = (globalPos - eGlobal + 52) % 52
                if (dist in 1..6) return true // An enemy can reach this position with a dice roll
            }
            return false
        }

        for (token in validMoves) {
            var score = 0
            
            // 5. Deploy/Release a token from the yard (if a 6 is rolled): +40 points
            if (token.steps == -1 && roll == 6) {
                score += 40
            } else if (token.steps != -1) {
                val newSteps = token.steps + roll
                val currentGlobalPos = (token.player * 13 + token.steps) % 52
                val newGlobalPos = (token.player * 13 + newSteps) % 52
                
                val currentlyVulnerable = if (token.steps in 0..50) isVulnerableAt(currentGlobalPos, token.player) else false
                val newlyVulnerable = if (newSteps <= 50) isVulnerableAt(newGlobalPos, token.player) else false
                
                // 1. Kill/Capture an opponent token: +100 points
                if (newSteps <= 50 && !safeCells.contains(newGlobalPos)) {
                    val killable = state.tokens.any {
                        it.player != token.player && 
                        !(state.gameMode == GameMode.TEAM && it.player == (token.player + 2) % 4) &&
                        it.steps in 0..50 && ((it.player * 13 + it.steps) % 52) == newGlobalPos
                    }
                    if (killable) score += 100
                }
                
                // 2. Enter the final Home goal/triangle: +80 points
                if (newSteps == 56) {
                    score += 80
                }
                
                // 3. Escape danger (moving out of an opponent's hitting zone into safety): +60 points
                if (currentlyVulnerable && !newlyVulnerable) {
                    score += 60
                }
                
                // 4. Land safely on a Star tile or safe zone: +50 points
                if (newSteps <= 50 && safeCells.contains(newGlobalPos)) {
                    score += 50
                }
                
                // Avoid moving into danger
                if (!currentlyVulnerable && newlyVulnerable) {
                    score -= 30
                }
                
                // 6. Progress furthest along the track (Default baseline)
                // Minor point multiplier to favor advancing tokens already far ahead
                score += (newSteps / 2)
            }
            
            if (score > bestScore) {
                bestScore = score
                bestMove = token
            }
        }
        
        return bestMove ?: validMoves.first()
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
        if (!state.hasRolled || state.currentPlayer != playerIndex || isGameOver()) return
        
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
        
        launch {
            val movedTokens = state.tokens.toMutableList()
            val index = movedTokens.indexOfFirst { it.id == tokenId && it.player == playerIndex }

            var killedOpponent = false
            var killGlobalPos = -1
            var actualFinalSteps = originalSteps
            val opponentsToKill = mutableListOf<TokenState>()
            val currentTrailPath = mutableListOf<Int>()
            val trailStartTime = System.currentTimeMillis()
            
            for (step in pathSteps) {
                actualFinalSteps = step
                
                movedTokens[index] = movedTokens[index].copy(steps = step)
                state = state.copy(tokens = movedTokens)
                playSound("token_hop")
                
                if (step != pathSteps.last()) {
                    kotlinx.coroutines.delay(200) // Hop pause
                }
                
                if (step in 0..50) {
                    currentTrailPath.add((playerIndex * 13 + step) % 52)
                }
                state = state.copy(trailState = TrailState(playerIndex, currentTrailPath.toList(), trailStartTime))

                if (step == pathSteps.last() && step in 0..50) {
                    val globalPos = (playerIndex * 13 + step) % 52
                    if (!safeCells.contains(globalPos)) {
                        val opponentsHere = movedTokens.filter {
                            it.player != playerIndex &&
                            !(state.gameMode == GameMode.TEAM && it.player == (playerIndex + 2) % 4) &&
                            it.steps in 0..50 &&
                            ((it.player * 13 + it.steps) % 52) == globalPos
                        }
                        if (opponentsHere.isNotEmpty()) {
                            opponentsToKill.add(opponentsHere.first())
                            killedOpponent = true
                            killGlobalPos = globalPos
                            break
                        }
                    }
                }
            }

            if (killedOpponent) {
                // 1. Trigger zoom and sound
                playSound("kill_strike")
                
                var isRevenge = false
                opponentsToKill.forEach { opp ->
                    if (opp.rivalPlayer == state.currentPlayer) {
                        isRevenge = true
                    }
                }
                
                state = state.copy(
                    lastKillTimestamp = System.currentTimeMillis(),
                    lastKillPos = killGlobalPos,
                    lastKillerId = playerIndex,
                    isRevengeEvent = isRevenge
                )
                
                // 2. Wait for zoom peak (cinematic scale takes its time)
                kotlinx.coroutines.delay(800)
                
                // 5. Remove victims and trigger ring
                val killedTokens = state.tokens.toMutableList()
                opponentsToKill.forEach { opp ->
                    val oppIndex = killedTokens.indexOfFirst { it.id == opp.id && it.player == opp.player }
                    if (oppIndex != -1) {
                         killedTokens[oppIndex] = killedTokens[oppIndex].copy(steps = -1, rivalPlayer = -1)
                    }
                }
                // Mark the killer as a rival (owned by the victim's player for the revenge logic)
                killedTokens[index] = killedTokens[index].copy(rivalPlayer = opponentsToKill.first().player)
                
                state = state.copy(tokens = killedTokens, killEventTimestamp = System.currentTimeMillis())
                
                // 6. Wait to show the effect before zoom out begins
                kotlinx.coroutines.delay(600)
            } else {
                state = state.copy(tokens = movedTokens)
            }

            val hasExtraTurn = state.diceValue == 6 || killedOpponent || actualFinalSteps == 56

            val winOrder = state.winOrder.toMutableList()
            val finalTokens = state.tokens
            val targetTokensHome = if (state.gameMode == GameMode.QUICK) 2 else 4
            val isPlayerFinished = finalTokens.filter { it.player == playerIndex && it.steps == 56 }.size >= targetTokensHome
            if (isPlayerFinished && !winOrder.contains(playerIndex)) {
                winOrder.add(playerIndex)
            }

            state = state.copy(winOrder = winOrder)

            val isGameOver = if (state.gameMode == GameMode.TEAM) {
                (winOrder.contains(0) && winOrder.contains(2)) || (winOrder.contains(1) && winOrder.contains(3))
            } else {
                winOrder.size >= state.playerModes.count { it.value != PlayerMode.INACTIVE } - 1
            }

            if (isGameOver) {
                val winningTeamMsg = if (state.gameMode == GameMode.TEAM) {
                    if (winOrder.contains(0)) "Team Red & Yellow" else "Team Green & Blue"
                } else playerName(winOrder[0])
                state = state.copy(message = "Game Over! Winner: $winningTeamMsg", hasRolled = true)
            } else if (hasExtraTurn && !isPlayerFinished) {
                 state = state.copy(
                     hasRolled = false,
                     message = "Extra turn! Roll again."
                 )
                 triggerAIIfNecessary(launch)
            } else {
                 nextPlayer()
                 triggerAIIfNecessary(launch)
            }
            
            // Handle trail fade out
            launch {
                kotlinx.coroutines.delay(1000)
                if (state.trailState.timestamp == trailStartTime) {
                    state = state.copy(trailState = state.trailState.copy(isFadingOut = true))
                    kotlinx.coroutines.delay(500)
                    if (state.trailState.timestamp == trailStartTime) {
                        state = state.copy(trailState = TrailState())
                    }
                }
            }
        }
    }

    private fun nextPlayer() {
        var next = (state.currentPlayer + 1) % 4
        while ((state.winOrder.contains(next) || state.playerModes[next] == PlayerMode.INACTIVE) && !isGameOver()) {
            next = (next + 1) % 4
        }
        state = state.copy(
            currentPlayer = next,
            hasRolled = false,
            message = "${playerName(next)}'s turn. Roll the dice!"
        )
    }

    fun triggerAIIfNecessary(onDelay: (suspend () -> Unit) -> Unit) {
        if (isGameOver()) return
        
        if (state.playerModes[state.currentPlayer] == PlayerMode.COMPUTER && !state.hasRolled) {
            onDelay {
                kotlinx.coroutines.delay(800)
                onRequestRoll()
            }
        }
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
    diceVisible: Boolean,
    rollTrigger: Int,
    timeRemainingMs: Long,
    onRoll: (Offset, Int?) -> Unit
) {
    val color = getPlayerColor(playerIdx)
    val alpha by animateFloatAsState(if (isActive) 1f else 0.4f)
    val scale by animateFloatAsState(if (isActive) 1.05f else 1f)
    
    val diceAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.4f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "diceAlpha"
    )

    val diceRotation = remember { androidx.compose.animation.core.Animatable(0f) }
    var isRolling by remember { mutableStateOf(false) }
    var visualValue by remember { mutableStateOf(1) }

    LaunchedEffect(rollTrigger) {
        if (rollTrigger > 0 && isActive) {
            isRolling = true
            diceRotation.snapTo(0f)
            launch {
                diceRotation.animateTo(
                    targetValue = 360f * 3f, // 3 full spins
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 250,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                )
            }
            launch {
                val endTime = System.currentTimeMillis() + 250
                while (System.currentTimeMillis() < endTime && isRolling) {
                    visualValue = kotlin.random.Random.nextInt(1, 7)
                    kotlinx.coroutines.delay(40)
                }
                visualValue = diceValue
                isRolling = false
            }
        } else if (!isRolling) {
            visualValue = diceValue
        }
    }

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
            .clickable(enabled = isActive) { onRoll(Offset(0f, -10f), null) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        val timerProgress = timeRemainingMs / 10000f
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isActive && timeRemainingMs > 0) {
                androidx.compose.material3.CircularProgressIndicator(
                     progress = { timerProgress },
                     modifier = Modifier.fillMaxSize(),
                     color = color,
                     strokeWidth = 3.dp,
                     trackColor = Color.LightGray.copy(alpha = 0.5f)
                )
            }
            Box(
                modifier = Modifier.size(32.dp).background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(45.dp)
                .graphicsLayer { 
                    this.alpha = diceAlpha
                    this.rotationZ = diceRotation.value
                    if (diceAlpha > 0f) shadowElevation = 8f
                    shape = RoundedCornerShape(12.dp) 
                }
                .background(color, RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            DiceDots(visualValue)
        }
    }
}



@Composable
fun LudoApp(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val soundManager = remember { com.example.SoundManager(context) }
    
    DisposableEffect(Unit) {
        onDispose {
            soundManager.release()
        }
    }
    
    val engine = remember(soundManager) { 
        GameEngine(playSound = { event -> 
            when (event) {
                "dice_roll" -> soundManager.playDiceRoll()
                "token_hop" -> soundManager.playTokenHop()
                "kill_strike" -> soundManager.playKillStrike()
                "win_sequence" -> soundManager.playWinSequence()
            }
        })
    }
    
    val scope = rememberCoroutineScope()
    
    var diceVisible by remember { mutableStateOf(false) }
    var diceRollTrigger by remember { mutableStateOf(0) }
    var currentDiceValue by remember { mutableStateOf(1) }

    // Smart Turn Timer
    var turnRemainingMs by remember { mutableStateOf(10000L) }
    
    // Cinematic Zoom Animation
    var cinematicScale by remember { mutableStateOf(1f) }
    val animCinematicScale by animateFloatAsState(targetValue = cinematicScale, animationSpec = spring(dampingRatio = 0.5f, stiffness = 100f))
    
    var isRevengeBannerVisible by remember { mutableStateOf(false) }
    val shakeOffsetX = remember { Animatable(0f) }
    val shakeOffsetY = remember { Animatable(0f) }

    var showVictoryScreen by remember { mutableStateOf(false) }
    var winningPlayerIndex by remember { mutableStateOf(-1) }

    var screenState by remember { mutableStateOf(ScreenState.MENU) }

    var menuPlayerCount by remember { mutableStateOf(4) }
    var menuGameMode by remember { mutableStateOf(GameMode.CLASSIC) }
    var menuModes by remember { mutableStateOf(mutableMapOf<Int, PlayerMode>(
        0 to PlayerMode.HUMAN,
        1 to PlayerMode.COMPUTER,
        2 to PlayerMode.HUMAN,
        3 to PlayerMode.COMPUTER
    )) }

    LaunchedEffect(engine.state.tokens, engine.state.currentPlayer) {
        diceVisible = false
    }

    LaunchedEffect(engine.state.winOrder) {
        if (engine.state.winOrder.isNotEmpty() && !showVictoryScreen) {
            winningPlayerIndex = engine.state.winOrder.first()
            showVictoryScreen = true
            soundManager.playWinSequence()
        }
    }

    val triggerRoll: (Offset, Int?) -> Unit = { dir, forcedValue ->
        if (!engine.state.hasRolled && !engine.isGameOver()) {
            val rollResult = forcedValue ?: Random.nextInt(1, 7)
            
            soundManager.playDiceRoll()
            currentDiceValue = rollResult
            diceVisible = true
            diceRollTrigger++

            scope.launch {
                kotlinx.coroutines.delay(250)
                engine.processRoll(rollResult) { action -> scope.launch { action() } }
            }
        }
    }

    LaunchedEffect(Unit) {
        engine.onRequestRoll = {
            triggerRoll(Offset.Zero, null)
        }
    }

    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }

    if (screenState == ScreenState.MENU) {
        Box(modifier = modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE8EAF6), Color(0xFFC5CAE9))))) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LUDO",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                    color = colorRed,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Active Players", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            listOf(2, 3, 4).forEach { count ->
                                val isSelected = menuPlayerCount == count
                                Button(
                                    onClick = { 
                                        soundManager.playButtonClick()
                                        menuPlayerCount = count 
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) colorBlue else Color(0xFFEEEEEE), contentColor = if (isSelected) Color.White else Color.Black),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("$count", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Game Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            listOf(
                                GameMode.CLASSIC to "Classic", 
                                GameMode.QUICK to "Quick", 
                                GameMode.TEAM to "Team"
                            ).forEach { (mode, name) ->
                                val isSelected = menuGameMode == mode
                                // Force 4 players for Team Mode
                                val enabled = if (mode == GameMode.TEAM) menuPlayerCount == 4 else true
                                Button(
                                    onClick = { 
                                        soundManager.playButtonClick()
                                        menuGameMode = mode 
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.padding(horizontal = 4.dp).weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) colorBlue else Color(0xFFEEEEEE), 
                                        contentColor = if (isSelected) Color.White else Color.Black,
                                        disabledContainerColor = Color(0xFFF5F5F5),
                                        disabledContentColor = Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1) }
                            }
                        }
                        if (menuGameMode == GameMode.TEAM && menuPlayerCount != 4) {
                            Text("Team mode requires 4 players", color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                
                // Map 2-player to index 0, 2; 3-player to 0, 1, 2; 4-player 0, 1, 2, 3.
                val activeIndices = when (menuPlayerCount) {
                    2 -> listOf(0, 2)
                    3 -> listOf(0, 1, 2)
                    else -> listOf(0, 1, 2, 3)
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                        Text("Player Control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.DarkGray, modifier = Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally))
                        
                        listOf(0, 1, 2, 3).forEach { pIdx ->
                            if (activeIndices.contains(pIdx)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically, 
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(16.dp).background(getPlayerColor(pIdx), CircleShape))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(engine.playerName(pIdx), color = Color.DarkGray, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                    
                                    val currentMode = menuModes[pIdx] ?: PlayerMode.HUMAN
                                    OutlinedButton(
                                        onClick = {
                                            menuModes = menuModes.toMutableMap().apply { this[pIdx] = if (currentMode == PlayerMode.HUMAN) PlayerMode.COMPUTER else PlayerMode.HUMAN }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (currentMode == PlayerMode.COMPUTER) Color(0xFFF3E5F5) else Color.Transparent),
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (currentMode == PlayerMode.COMPUTER) Color(0xFF9C27B0) else Color.LightGray)
                                    ) { Text(if (currentMode == PlayerMode.HUMAN) "👤 HUMAN" else "🤖 CPU", color = if (currentMode == PlayerMode.COMPUTER) Color(0xFF9C27B0) else Color.Gray, fontWeight = FontWeight.Bold) }
                                }
                            } else {
                                menuModes[pIdx] = PlayerMode.INACTIVE
                            }
                        }
                    }
                }
                
                Button(
                    onClick = {
                        engine.initGame(menuModes, menuGameMode)
                        screenState = ScreenState.PLAYING
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colorGreen),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("START MATCH", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp) }
            }
        }
        return
    }

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
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(1, "Green", engine.state.currentPlayer == 1, currentDiceValue, diceVisible, diceRollTrigger, turnRemainingMs, triggerRoll)
                }
                
                var showOptionsMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Text("⋮", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Restart Game") },
                            onClick = {
                                showOptionsMenu = false
                                engine.initGame(engine.state.playerModes, engine.state.gameMode)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Main Menu (Home)") },
                            onClick = {
                                showOptionsMenu = false
                                screenState = ScreenState.MENU
                            }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(2, "Yellow", engine.state.currentPlayer == 2, currentDiceValue, diceVisible, diceRollTrigger, turnRemainingMs, triggerRoll)
                }
            }

            LaunchedEffect(engine.state.currentPlayer, engine.state.hasRolled, engine.state.tokens) {
                if (engine.state.playerModes[engine.state.currentPlayer] == PlayerMode.HUMAN) {
                    turnRemainingMs = 10000L
                    while(turnRemainingMs > 0) {
                        delay(100)
                        turnRemainingMs -= 100
                    }
                    if (!engine.state.hasRolled) {
                        triggerRoll(Offset.Zero, null)
                    } else {
                        val bestMove = engine.getBestMove()
                        if (bestMove != null) {
                            engine.moveToken(bestMove.id, bestMove.player) { action -> scope.launch { action() } }
                        }
                    }
                }
            }
            
            LaunchedEffect(engine.state.lastKillTimestamp) {
                if (engine.state.lastKillTimestamp > 0) {
                    cinematicScale = if (engine.state.isRevengeEvent) 1.65f else 1.45f
                    if (engine.state.isRevengeEvent) {
                        isRevengeBannerVisible = true
                        launch {
                            for(i in 0..15) {
                                shakeOffsetX.snapTo(if (i % 2 == 0) 15f else -15f)
                                shakeOffsetY.snapTo(if (i % 3 == 0) 10f else -10f)
                                delay(30)
                            }
                            shakeOffsetX.snapTo(0f)
                            shakeOffsetY.snapTo(0f)
                        }
                    }
                    delay(1500)
                    cinematicScale = 1f
                    isRevengeBannerVisible = false
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
            
            val trailFadeAlpha by animateFloatAsState(
                targetValue = if (engine.state.trailState.isFadingOut) 0f else 1f,
                animationSpec = tween(500),
                label = "trailFadeAlpha"
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = animCinematicScale
                        scaleY = animCinematicScale
                        translationX = shakeOffsetX.value
                        translationY = shakeOffsetY.value
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
                              val ringColor = if (engine.state.lastKillerId != -1) getPlayerColor(engine.state.lastKillerId) else Color.Red
                              drawCircle(
                                  color = ringColor.copy(alpha = killRingAlpha.value),
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
                                
                                // Trail rendering
                                val trailState = engine.state.trailState
                                if (globalIdx != -1 && trailState.path.contains(globalIdx)) {
                                    val trailIndex = trailState.path.indexOf(globalIdx)
                                    val opacityScale = (trailIndex + 1f) / trailState.path.size
                                    val trailAlpha = opacityScale * 0.7f * trailFadeAlpha
                                    if (trailAlpha > 0f) {
                                        val c = Color(0xFF00E5FF).copy(alpha = trailAlpha)
                                        drawRect(color = c, topLeft = Offset(col * cxPx, row * cxPx), size = Size(cxPx, cxPx))
                                    }
                                }

                                // Danger Zone Shading
                                if (globalIdx != -1 && !safeCells.contains(globalIdx)) {
                                    val tokensHere = engine.state.tokens.filter { it.steps in 0..50 && ((it.player * 13 + it.steps)%52 == globalIdx) }
                                    if (tokensHere.isNotEmpty()) {
                                        var inDanger = false
                                        val herePlayer = tokensHere.first().player
                                        for (pl in 0..3) {
                                            if (pl == herePlayer || (engine.state.gameMode == GameMode.TEAM && pl == (herePlayer + 2) % 4)) continue
                                            val enemies = engine.state.tokens.filter { it.player == pl && it.steps in 0..50 }
                                            for (e in enemies) {
                                                val eGlobal = (e.player * 13 + e.steps) % 52
                                                val dist = (globalIdx - eGlobal + 52) % 52
                                                if (dist in 1..6) {
                                                    inDanger = true
                                                    break
                                                }
                                            }
                                            if (inDanger) break
                                        }
                                        if (inDanger) {
                                            drawRect(color = Color.Red.copy(alpha = 0.35f), topLeft = Offset(col * cxPx, row * cxPx), size = Size(cxPx, cxPx))
                                        }
                                    }
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
                    
                    
                    val animX by animateDpAsState(targetValue = targetX, animationSpec = tween(200, easing = LinearOutSlowInEasing))
                    val animY by animateDpAsState(targetValue = targetY, animationSpec = tween(200, easing = LinearOutSlowInEasing))
                    
                    
                    val isMyTurnToken = engine.state.currentPlayer == token.player && engine.state.hasRolled && engine.isValidMove(token, currentDiceValue)
                    
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
                        if (token.rivalPlayer != -1) {
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .border(5.dp, getPlayerColor(token.rivalPlayer).copy(alpha = 0.4f), CircleShape)
                            )
                        }
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
                
                val cellPxSize = maxWidth / 15
                engine.state.winOrder.forEachIndexed { rankIndex, playerIdx ->
                    val rankText = when (rankIndex) {
                        0 -> "1st"
                        1 -> "2nd"
                        2 -> "3rd"
                        else -> "4th"
                    }

                    val alignModifier = when (playerIdx) {
                        0 -> Modifier.align(Alignment.BottomStart) // Red
                        1 -> Modifier.align(Alignment.TopStart) // Green
                        2 -> Modifier.align(Alignment.TopEnd) // Yellow
                        3 -> Modifier.align(Alignment.BottomEnd) // Blue
                        else -> Modifier
                    }

                    Box(modifier = alignModifier.size(cellPxSize * 6), contentAlignment = Alignment.Center) {
                        Text(
                            text = rankText,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .graphicsLayer {
                                    shadowElevation = 16f
                                    shape = RoundedCornerShape(12.dp)
                                }
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Bottom UI: Red and Blue
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(0, "Red", engine.state.currentPlayer == 0, currentDiceValue, diceVisible, diceRollTrigger, turnRemainingMs, triggerRoll)
                }
                Box(modifier = Modifier.weight(1f)) {
                    PlayerPanel(3, "Blue", engine.state.currentPlayer == 3, currentDiceValue, diceVisible, diceRollTrigger, turnRemainingMs, triggerRoll)
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



        if (showVictoryScreen && winningPlayerIndex != -1) {
            VictoryScreen(winnerIndex = winningPlayerIndex) {
                showVictoryScreen = false
            }
        }
        
        androidx.compose.animation.AnimatedVisibility(
            visible = isRevengeBannerVisible,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { -it }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xAA000000), Color.Transparent)))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "REVENGE!",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.Red,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    modifier = Modifier.graphicsLayer {
                        shadowElevation = 16f
                    }
                )
            }
        }
    }
}

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var color: androidx.compose.ui.graphics.Color,
    var rotation: Float,
    var rotationSpeed: Float
)

fun getPlayerName(index: Int) = when(index) {
    0 -> "Red"
    1 -> "Green"
    2 -> "Yellow"
    3 -> "Blue"
    else -> "Unknown"
}

@Composable
fun VictoryScreen(winnerIndex: Int, onDismiss: () -> Unit) {
    val winningPlayerName = getPlayerName(winnerIndex)
    val color = getPlayerColor(winnerIndex)
    
    val scale = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    
    val particles = remember {
        List(150) {
            Particle(
                x = kotlin.random.Random.nextFloat(),
                y = kotlin.random.Random.nextFloat() * -1f - 0.2f,
                vx = (kotlin.random.Random.nextFloat() - 0.5f) * 0.3f,
                vy = kotlin.random.Random.nextFloat() * 1.5f + 1f,
                color = listOf(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color(0xFFFFD700), androidx.compose.ui.graphics.Color.Blue).random(),
                rotation = kotlin.random.Random.nextFloat() * 360f,
                rotationSpeed = (kotlin.random.Random.nextFloat() - 0.5f) * 15f
            )
        }.toMutableStateList()
    }

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(16, easing = androidx.compose.animation.core.LinearEasing))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
            .clickable { onDismiss() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val _ignore = frame
            particles.forEach { p ->
                p.x += p.vx * 0.016f
                p.y += p.vy * 0.016f
                p.rotation += p.rotationSpeed
                if (p.y > 1.2f) {
                    p.y = -0.2f
                    p.x = kotlin.random.Random.nextFloat()
                }
                
                withTransform({
                    translate(p.x * size.width, p.y * size.height)
                    rotate(p.rotation)
                }) {
                    drawRect(p.color, topLeft = Offset(-10f, -10f), size = androidx.compose.ui.geometry.Size(20f, 20f))
                }
            }
        }
        
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(24.dp))
                .border(4.dp, color, RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Text(
                text = "VICTORY!",
                fontSize = 48.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                color = color
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.Text(
                text = "$winningPlayerName Wins",
                fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.DarkGray
            )
        }
    }
}