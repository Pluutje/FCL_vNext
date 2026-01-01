package app.aaps.plugins.aps.openAPSFCL.vnext

import android.R
import org.joda.time.DateTime
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCoreParameterSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogger
import kotlin.collections.get
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextParameterLogger

import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextProfileParameterSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextProfile
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextProfiles
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextProfileTuning
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningAdvisor
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningPersistence

data class FCLvNextInput(
    val bgNow: Double,                          // mmol/L
    val bgHistory: List<Pair<DateTime, Double>>, // mmol/L
    val currentIOB: Double,
    val maxIOB: Double,
    val effectiveISF: Double,                   // mmol/L per U
    val targetBG: Double,                       // mmol/L
    val isNight: Boolean
)

data class FCLvNextContext(
    val input: FCLvNextInput,

    // trends
    val slope: Double,          // mmol/L per uur
    val acceleration: Double,   // mmol/L per uur²
    val consistency: Double,    // 0..1

    // relatieve veiligheid
    val iobRatio: Double,       // currentIOB / maxIOB

    // afstand tot target
    val deltaToTarget: Double   // bgNow - targetBG
)

data class FCLvNextAdvice(
    val bolusAmount: Double,
    val basalRate: Double,
    val shouldDeliver: Boolean,

    // feedback naar determineBasal
    val effectiveISF: Double,
    val targetAdjustment: Double,

    // debug / UI
    val statusText: String
)

private data class DecisionResult(
    val allowed: Boolean,
    val force: Boolean,
    val dampening: Double,
    val reason: String
)

private data class ExecutionResult(
    val bolus: Double,
    val basalRate: Double,      // U/h (temp basal command; AAPS wordt elke cycle vernieuwd)
    val deliveredTotal: Double  // bolus + (basalRate * cycleHours)
)

private enum class MealState { NONE, UNCERTAIN, CONFIRMED }
private enum class TrendState {NONE, RISING_WEAK, RISING_CONFIRMED }
private enum class PeakCategory {NONE, MILD, MEAL, HIGH, EXTREME }
private enum class BgZone { LOW, IN_RANGE, MID, HIGH, EXTREME }
// ── RESCUE (hypo-prevent carbs) DETECTOR (persistent) ──
private enum class RescueState { IDLE, ARMED, CONFIRMED }
private enum class PeakPredictionState {IDLE, WATCHING, CONFIRMED }
private enum class DoseAccessLevel {BLOCKED, MICRO_ONLY, SMALL, NORMAL }


private data class MealSignal(
    val state: MealState,
    val confidence: Double,     // 0..1
    val reason: String
)



private data class TrendDecision(
    val state: TrendState,
    val reason: String
)

/**
 * Stap 1: simpele trend-poort die snacks/ruis weert.
 * - WEAK: probe mag, maar geen grote acties
 * - CONFIRMED: grote acties toegestaan
 */
private fun classifyTrendState(ctx: FCLvNextContext, config: FCLvNextConfig): TrendDecision {

    // basis betrouwbaarheid
    if (ctx.consistency < config.minConsistency) {
        return TrendDecision(TrendState.NONE, "TREND none: low consistency")
    }

    // thresholds (startwaarden; later tunen op echte meals)
    val weakSlopeMin = 0.45
    val weakAccelMin = 0.10

    val confSlopeMin = 0.95
    val confAccelMin = 0.18
    val confDeltaMin = 1.8

    val slope = ctx.slope
    val accel = ctx.acceleration
    val delta = ctx.deltaToTarget

    // dalend/flat -> NONE
    if (slope <= 0.15 && accel <= 0.05) {
        return TrendDecision(
            TrendState.NONE,
            "TREND none: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)}"
        )
    }

    // CONFIRMED (alleen als ook delta echt boven target is)
    val confirmed =
        slope >= confSlopeMin &&
            accel >= confAccelMin &&
            delta >= confDeltaMin

    if (confirmed) {
        return TrendDecision(
            TrendState.RISING_CONFIRMED,
            "TREND confirmed: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
        )
    }

    // WEAK (voorzichtig)
    val weak =
        slope >= weakSlopeMin &&
            accel >= weakAccelMin

    if (weak) {
        return TrendDecision(
            TrendState.RISING_WEAK,
            "TREND weak: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
        )
    }

    return TrendDecision(
        TrendState.NONE,
        "TREND none: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
    )
}

private fun allowsLargeActions(
    trend: TrendState,
    persistentConfirmed: Boolean
): Boolean =
    trend == TrendState.RISING_CONFIRMED && persistentConfirmed


// ─────────────────────────────────────────────
// Meal-episode peak estimator (persistent over cycles)
// ─────────────────────────────────────────────

private data class PeakEstimatorContext(
    var active: Boolean = false,
    var startedAt: DateTime? = null,
    var startBg: Double = 0.0,

    // memory features
    var maxSlope: Double = 0.0,          // mmol/L/h
    var maxAccel: Double = 0.0,          // mmol/L/h²
    var posSlopeArea: Double = 0.0,      // mmol/L (∫ max(0,slope) dt)
    var momentum: Double = 0.0,          // mmol/L (decayed posSlopeArea)
    var lastAt: DateTime? = null,

    // state machine
    var state: PeakPredictionState = PeakPredictionState.IDLE,
    var confirmCounter: Int = 0
)

private data class PeakEstimate(
    val state: PeakPredictionState,
    val predictedPeak: Double,
    val peakBand: Int,              // 10/12/15/20 bucket (of 0 als <10)
    val maxSlope: Double,
    val momentum: Double,
    val riseSinceStart: Double
)



private fun classifyPeak(predictedPeak: Double): PeakCategory {
    return when {
        predictedPeak >= 17.5 -> PeakCategory.EXTREME
        predictedPeak >= 14.5 -> PeakCategory.HIGH
        predictedPeak >= 11.8 -> PeakCategory.MEAL
        predictedPeak >= 9.8  -> PeakCategory.MILD
        else -> PeakCategory.NONE
    }
}



private fun computeBgZone(ctx: FCLvNextContext): BgZone {
    val delta = ctx.deltaToTarget  // bgNow - target

    return when {
        ctx.input.bgNow <= 4.4 -> BgZone.LOW                    // absolute hypo zone
        delta <= 0.6 -> BgZone.IN_RANGE                         // dicht bij target
        delta <= 2.0 -> BgZone.MID                              // licht/matig boven target
        delta <= 4.5 -> BgZone.HIGH                             // duidelijk hoog
        else -> BgZone.EXTREME                                  // zeer hoog
    }
}

private fun computeDoseAccessLevel(
    ctx: FCLvNextContext,
    bgZone: BgZone
): DoseAccessLevel {

    return when (bgZone) {

        BgZone.LOW ->
            DoseAccessLevel.BLOCKED

        BgZone.IN_RANGE -> when {
            ctx.slope < 0.6 || ctx.acceleration < 0.10 ->
                DoseAccessLevel.BLOCKED
            ctx.slope < 0.9 ->
                DoseAccessLevel.MICRO_ONLY
            else ->
                DoseAccessLevel.SMALL
        }

        BgZone.MID -> when {
            ctx.slope < 0.5 ->
                DoseAccessLevel.MICRO_ONLY
            ctx.slope < 0.9 ->
                DoseAccessLevel.SMALL
            else ->
                DoseAccessLevel.NORMAL
        }

        BgZone.HIGH ->
            DoseAccessLevel.NORMAL

        BgZone.EXTREME ->
            DoseAccessLevel.NORMAL
    }
}




private val peakEstimator = PeakEstimatorContext()


private var lastCommitAt: DateTime? = null
private var lastCommitDose: Double = 0.0
private var lastCommitReason: String = ""

private var lastReentryCommitAt: DateTime? = null

// ── EARLY DOSE CONTROLLER (persistent) ──
private data class EarlyDoseContext(
    var stage: Int = 0,              // 0=none, 1=probe, 2=boost
    var lastFireAt: DateTime? = null,
    var lastConfidence: Double = 0.0
)

private var earlyDose = EarlyDoseContext()

// ── PRE-PEAK IMPULSE STATE ──
private var prePeakImpulseDone: Boolean = false
private var lastSegmentAt: DateTime? = null

private var lastSmallCorrectionAt: DateTime? = null

private var earlyConfirmDone: Boolean = false

private var trendConfirmCounter: Int = 0

private val persistCtrl = PersistentCorrectionController(
    cooldownCycles = 3,        // of 2, jij kiest
    maxBolusFraction = 0.30
)



private data class RescueDetectionContext(
    var state: RescueState = RescueState.IDLE,
    var armedAt: DateTime? = null,
    var armedBg: Double = 0.0,
    var armedPred60: Double = 0.0,
    var armedSlope: Double = 0.0,
    var armedAccel: Double = 0.0,
    var armedIobRatio: Double = 0.0,
    var lastConfirmAt: DateTime? = null,
    var lastReason: String = "",
    var confidence: Double = 0.0
)

private var rescue = RescueDetectionContext()


// ─────────────────────────────────────────────
// Peak prediction state (persistent over cycles)
// ─────────────────────────────────────────────



private data class PeakPredictionContext(
    var state: PeakPredictionState = PeakPredictionState.IDLE,
    var confirmCounter: Int = 0
)

private val peakPrediction = PeakPredictionContext()

private const val MAX_DELIVERY_HISTORY = 5




val deliveryHistory: ArrayDeque<Pair<DateTime, Double>> =
    ArrayDeque()

private fun calculateEnergy(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Double {

    val positional = ctx.deltaToTarget * config.kDelta
    val kinetic = ctx.slope * config.kSlope
    val accelerationBoost = ctx.acceleration * config.kAccel

    var energy = positional + kinetic + accelerationBoost

    // betrouwbaarheid (exponentieel)
    val consistency = ctx.consistency
        .coerceAtLeast(config.minConsistency)
        .let { Math.pow(it, config.consistencyExp) }

    energy *= consistency

    return energy
}

private fun calculateStagnationBoost(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Double {

    val active =
        ctx.deltaToTarget >= config.stagnationDeltaMin &&
            ctx.slope > config.stagnationSlopeMaxNeg &&
            ctx.slope < config.stagnationSlopeMaxPos &&
            kotlin.math.abs(ctx.acceleration) <= config.stagnationAccelMaxAbs &&
            ctx.consistency >= config.minConsistency

    if (!active) return 0.0

    return config.stagnationEnergyBoost * ctx.deltaToTarget
}



private fun energyToInsulin(
    energy: Double,
    effectiveISF: Double,
    config: FCLvNextConfig
): Double {
    if (energy <= 0.0) return 0.0
    return (energy / effectiveISF) * config.gain
}



private fun decide(ctx: FCLvNextContext): DecisionResult {

    // === LAYER A — HARD STOPS ===
    if (ctx.consistency < 0.2) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: unreliable data"
        )
    }

    if (ctx.iobRatio > 1.1) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: IOB saturated"
        )
    }

    // === LAYER C — FORCE ALLOW ===
    if (ctx.slope > 2.0 && ctx.acceleration > 0.5 && ctx.consistency > 0.6) {
        return DecisionResult(
            allowed = true,
            force = true,
            dampening = 1.0,
            reason = "Force: strong rising trend"
        )
    }

    // === LAYER B — SOFT ALLOW ===
    val nightFactor = if (ctx.input.isNight) 0.7 else 1.0
    val consistencyFactor = ctx.consistency.coerceIn(0.3, 1.0)

    return DecisionResult(
        allowed = true,
        force = false,
        dampening = nightFactor * consistencyFactor,
        reason = "Soft allow"
    )
}

private fun updatePeakEstimate(
    config: FCLvNextConfig,
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    now: DateTime
): PeakEstimate {

    // ── episode start condities (flexibel, maar bewust niet te streng) ──
    val episodeShouldBeActive =
        mealSignal.state != MealState.NONE ||
            ( ctx.consistency >= 0.45 &&
                    ( ctx.deltaToTarget >= 0.6 ||
                            (ctx.acceleration >= 0.12 && ctx.slope >= 0.15)
                        )
                )

    // ── episode init/reset ──
    if (!peakEstimator.active && episodeShouldBeActive) {
        peakEstimator.active = true
        peakEstimator.startedAt = now
        peakEstimator.startBg = ctx.input.bgNow
        peakEstimator.maxSlope = ctx.slope.coerceAtLeast(0.0)
        peakEstimator.maxAccel = ctx.acceleration.coerceAtLeast(0.0)
        peakEstimator.posSlopeArea = 0.0
        peakEstimator.momentum = 0.0
        peakEstimator.lastAt = now
        peakEstimator.state = PeakPredictionState.IDLE
        peakEstimator.confirmCounter = 0
        // nieuw segment → nieuwe pre-peak impuls toegestaan
        prePeakImpulseDone = false
        lastSegmentAt = now
        earlyDose = EarlyDoseContext()
        earlyConfirmDone = false
    }

    // ── episode exit (niet te snel!) ──
    if (peakEstimator.active && !episodeShouldBeActive) {
        // exit pas als we echt “uit de meal dynamiek” zijn:
        val fallingClearly = ctx.slope <= -0.6 && ctx.consistency >= 0.55
        val lowDelta = ctx.deltaToTarget < 0.2 && ctx.acceleration <= 0.0
        if (fallingClearly || lowDelta) {
            peakEstimator.active = false
            peakEstimator.state = PeakPredictionState.IDLE
            peakEstimator.confirmCounter = 0
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false
        }
    }

    // ── update memory features ──
    val last = peakEstimator.lastAt ?: now
    val dtMin = org.joda.time.Minutes.minutesBetween(last, now).minutes.coerceAtLeast(0)
    val dtH = (dtMin / 60.0).coerceAtMost(0.2) // cap dt om rare jumps te dempen

    peakEstimator.lastAt = now

    if (peakEstimator.active && dtH > 0.0) {
        peakEstimator.maxSlope = maxOf(peakEstimator.maxSlope, ctx.slope.coerceAtLeast(0.0))
        peakEstimator.maxAccel = maxOf(peakEstimator.maxAccel, ctx.acceleration.coerceAtLeast(0.0))

        val pos = maxOf(0.0, ctx.slope) * dtH             // mmol/L
        peakEstimator.posSlopeArea += pos

        // momentum met half-life (zodat korte plateaus niet meteen alles resetten)
        val halfLifeMin = config.peakMomentumHalfLifeMin
        val decay = Math.pow(0.5, dtMin / halfLifeMin.coerceAtLeast(1.0))
        peakEstimator.momentum = peakEstimator.momentum * decay + pos
    }

    val riseSinceStart =
        if (peakEstimator.active) (ctx.input.bgNow - peakEstimator.startBg).coerceAtLeast(0.0) else 0.0

    // ── peak voorspelling: neem max van meerdere “conservatief vroege” schatters ──
    val h = config.peakPredictionHorizonH

    // 1) lokaal (maar nooit dominant als slope even wegvalt)
    val localV = maxOf(0.0, ctx.slope)
    val localA = maxOf(0.0, ctx.acceleration)

    val localBallistic =
        ctx.input.bgNow + localV * h + 0.5 * localA * h * h

    // 2) episode ballistic (houdt eerdere max-snelheid vast)
    val memV = maxOf(localV, config.peakUseMaxSlopeFrac * peakEstimator.maxSlope)
    val memA = maxOf(localA, config.peakUseMaxAccelFrac * peakEstimator.maxAccel)

    val memoryBallistic =
        ctx.input.bgNow + memV * h + 0.5 * memA * h * h

    // 3) momentum-based carry (integraal van stijging → “er komt nog meer”)
    val momentumCarry =
        ctx.input.bgNow + config.peakMomentumGain * peakEstimator.momentum

    // 4) rise-so-far scaling (als we al X mmol gestegen zijn, dan is “10” vaak te laag)
    val riseCarry =
        ctx.input.bgNow + config.peakRiseGain * riseSinceStart

    var predictedPeak = maxOf(localBallistic, memoryBallistic, momentumCarry, riseCarry)

    // nooit onder huidige BG
    predictedPeak = predictedPeak.coerceAtLeast(ctx.input.bgNow)

    // optionele bovengrens (veilig tegen explode door rare accel)
    predictedPeak = predictedPeak.coerceIn(ctx.input.bgNow, config.peakPredictionMaxMmol)

    // ── state machine (watch/confirm) op basis van predictedPeak + momentum ──
    val threshold = config.peakPredictionThreshold
    val enoughMomentum = peakEstimator.momentum >= config.peakMinMomentum
    val enoughConsistency = ctx.consistency >= config.peakMinConsistency

    when (peakEstimator.state) {
        PeakPredictionState.IDLE -> {
            if (predictedPeak >= threshold && enoughConsistency && (localV >= config.peakMinSlope || peakEstimator.maxSlope >= config.peakMinSlope) && enoughMomentum) {
                peakEstimator.state = PeakPredictionState.WATCHING
                peakEstimator.confirmCounter = 1
            }
        }

        PeakPredictionState.WATCHING -> {
            if (predictedPeak >= threshold && enoughConsistency && enoughMomentum) {
                peakEstimator.confirmCounter++
                if (peakEstimator.confirmCounter >= config.peakConfirmCycles) {
                    peakEstimator.state = PeakPredictionState.CONFIRMED
                }
            } else {
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
            }
        }

        PeakPredictionState.CONFIRMED -> {
            // exit zodra we echt post-peak zijn (jouw config-waarden)
            if (ctx.acceleration < config.peakExitAccel || ctx.slope < config.peakExitSlope) {
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
            }
        }
    }

    val band = when {
        predictedPeak >= 20.0 -> 20
        predictedPeak >= 15.0 -> 15
        predictedPeak >= 12.0 -> 12
        predictedPeak >= 10.0 -> 10
        else -> 0
    }

    return PeakEstimate(
        state = peakEstimator.state,
        predictedPeak = predictedPeak,
        peakBand = band,
        maxSlope = peakEstimator.maxSlope,
        momentum = peakEstimator.momentum,
        riseSinceStart = riseSinceStart
    )
}



private fun executeDelivery(
    dose: Double,
    hybridPercentage: Int,
    cycleMinutes: Int = 5,           // AAPS-cycle (typisch 5 min)
    maxTempBasalRate: Double = 25.0, // pomp/driver limiet (later pref)
    bolusStep: Double = 0.05,        // SMB stap
    basalRateStep: Double = 0.05,    // rate stap in U/h
    minSmb: Double = 0.05,
    smallDoseThreshold: Double = 0.4
): ExecutionResult {

    val cycleH = (cycleMinutes / 60.0).coerceAtLeast(1.0 / 60.0) // nooit 0
    val maxBasalUnitsThisCycle = (maxTempBasalRate.coerceAtLeast(0.0) * cycleH).coerceAtLeast(0.0)

    // helper: zet units -> rate, clamp en round
    fun unitsToRoundedRate(units: Double): Double {
        if (units <= 0.0) return 0.0
        val wantedRate = units / cycleH
        val cappedRate = wantedRate.coerceAtMost(maxTempBasalRate.coerceAtLeast(0.0))
        return roundToStep(cappedRate, basalRateStep).coerceAtLeast(0.0)
    }

    // 0) niets te doen: stuur expliciet 0-rate zodat lopende temp basal niet doorloopt
    if (dose <= 0.0) {
        return ExecutionResult(
            bolus = 0.0,
            basalRate = 0.0,
            deliveredTotal = 0.0
        )
    }

    // 1) Alle doses < smallDoseThreshold → volledig basaal
    if (dose < smallDoseThreshold || hybridPercentage <= 0) {

        val basalUnitsPlanned = dose.coerceAtMost(maxBasalUnitsThisCycle)
        val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
        val basalUnitsDelivered = basalRateRounded * cycleH

        // Eventueel restant (door cap/afronding) alsnog via SMB
        val missing = (dose - basalUnitsDelivered).coerceAtLeast(0.0)
        val bolusRounded =
            if (missing >= minSmb)
                roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
            else
                0.0

        return ExecutionResult(
            bolus = bolusRounded,
            basalRate = basalRateRounded,
            deliveredTotal = basalUnitsDelivered + bolusRounded
        )
    }

    // 3) hybride split (units), maar: bolus-deel < minSmb => schuif naar basaal (geen SMB-only!)
    val hp = hybridPercentage.coerceIn(0, 100)
    var basalUnitsWanted = dose * (hp / 100.0)
    var bolusUnitsWanted = (dose - basalUnitsWanted).coerceAtLeast(0.0)

    if (bolusUnitsWanted in 0.0..(minSmb - 1e-9)) {
        basalUnitsWanted = dose
        bolusUnitsWanted = 0.0
    }

    // 4) bolus afronden (kan 0 worden als we alles basaal willen)
    var bolusRounded = if (bolusUnitsWanted >= minSmb) {
        roundToStep(bolusUnitsWanted, bolusStep)
            .coerceAtLeast(minSmb)
            .coerceAtMost(dose)
    } else 0.0

    // 5) resterende units naar basaal, maar cap op wat in deze cycle kan
    val remainingForBasal = (dose - bolusRounded).coerceAtLeast(0.0)
    val basalUnitsPlanned = remainingForBasal.coerceAtMost(maxBasalUnitsThisCycle)

    val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
    val basalUnitsDelivered = basalRateRounded * cycleH

    // 6) wat we niet kwijt konden via basaal (cap/rounding) => als extra SMB proberen
    val missing = (remainingForBasal - basalUnitsDelivered).coerceAtLeast(0.0)

    if (missing >= minSmb) {
        val extraBolus = roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
        bolusRounded = (bolusRounded + extraBolus).coerceAtMost(dose)
    }

    val deliveredTotal = bolusRounded + basalUnitsDelivered

    return ExecutionResult(
        bolus = bolusRounded,
        basalRate = basalRateRounded,
        deliveredTotal = deliveredTotal
    )
}



private fun iobDampingFactor(
    iobRatio: Double,
    config: FCLvNextConfig,
    power: Double
): Double {

    val r = iobRatio.coerceIn(0.0, 2.0)

    if (r <= config.iobStart) return 1.0
    if (r >= config.iobMax) return config.iobMinFactor

    val x = ((r - config.iobStart) /
        (config.iobMax - config.iobStart))
        .coerceIn(0.0, 1.0)

    val shaped = 1.0 - Math.pow(x, power)

    return (config.iobMinFactor +
        (1.0 - config.iobMinFactor) * shaped)
        .coerceIn(config.iobMinFactor, 1.0)
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return (kotlin.math.round(value / step) * step)
}

private fun clamp(value: Double, min: Double, max: Double): Double {
    return value.coerceIn(min, max)
}


private fun detectMealSignal(ctx: FCLvNextContext, config: FCLvNextConfig): MealSignal {

    // basisvoorwaarden: voldoende data
    if (ctx.consistency < config.minConsistency) {
        return MealSignal(MealState.NONE, 0.0, "Low consistency")
    }

    val rising = ctx.slope > config.mealSlopeMin
    val accelerating = ctx.acceleration > config.mealAccelMin
    val aboveTarget = ctx.deltaToTarget > config.mealDeltaMin

    // confidence: combineer factoren (simpel, maar werkt)
    val slopeScore = ((ctx.slope - config.mealSlopeMin) / config.mealSlopeSpan).coerceIn(0.0, 1.0)
    val accelScore = ((ctx.acceleration - config.mealAccelMin) / config.mealAccelSpan).coerceIn(0.0, 1.0)
    val deltaScore = ((ctx.deltaToTarget - config.mealDeltaMin) / config.mealDeltaSpan).coerceIn(0.0, 1.0)

    val confidence =
        (0.45 * slopeScore + 0.35 * accelScore + 0.20 * deltaScore)
            .coerceIn(0.0, 1.0)

    // state
    val state = when {
        rising && accelerating && aboveTarget && confidence >= config.mealConfirmConfidence ->
            MealState.CONFIRMED

        (rising || accelerating) && aboveTarget && confidence >= config.mealUncertainConfidence ->
            MealState.UNCERTAIN

        else -> MealState.NONE
    }

    val reason = "MealSignal=$state conf=${"%.2f".format(confidence)}"
    return MealSignal(state, confidence, reason)
}

private fun canCommitNow(now: DateTime, config: FCLvNextConfig): Boolean {
    val last = lastCommitAt ?: return true
    val minutes = org.joda.time.Minutes.minutesBetween(last, now).minutes
    return minutes >= config.commitCooldownMinutes
}

private fun commitFractionZoneFactor(bgZone: BgZone): Double {
    return when (bgZone) {
        BgZone.LOW -> 0.0
        BgZone.IN_RANGE -> 0.55
        BgZone.MID -> 0.75
        BgZone.HIGH -> 1.00
        BgZone.EXTREME -> 1.10
    }
}


private fun computeCommitFraction(signal: MealSignal, config: FCLvNextConfig): Double {
    return when (signal.state) {
        MealState.NONE -> 0.0

        MealState.UNCERTAIN -> {
            // 0.45..0.70 afhankelijk van confidence
            val t = ((signal.confidence - config.mealUncertainConfidence) /
                (config.mealConfirmConfidence - config.mealUncertainConfidence))
                .coerceIn(0.0, 1.0)
            (config.uncertainMinFraction + t * (config.uncertainMaxFraction - config.uncertainMinFraction))
        }

        MealState.CONFIRMED -> {
            // 0.70..1.00 afhankelijk van confidence
            val t = ((signal.confidence - config.mealConfirmConfidence) /
                (1.0 - config.mealConfirmConfidence))
                .coerceIn(0.0, 1.0)
            (config.confirmMinFraction + t * (config.confirmMaxFraction - config.confirmMinFraction))
        }
    }.coerceIn(0.0, 1.0)
}

private fun minutesSince(ts: DateTime?, now: DateTime): Int {
    if (ts == null) return Int.MAX_VALUE
    return org.joda.time.Minutes.minutesBetween(ts, now).minutes
}

private fun isInAbsorptionWindow(now: DateTime, config: FCLvNextConfig): Boolean {
    val m = minutesSince(lastCommitAt, now)
    return m in 0..config.absorptionWindowMinutes
}

/**
 * Detecteert "rond/na piek" gedrag: we willen dosing stoppen of sterk reduceren.
 * Logica:
 * - Alleen relevant binnen absorptionWindow na een commit
 * - Als accel negatief wordt (afremmen) OF slope bijna nul/negatief -> absorptie/peak
 */
private fun shouldSuppressForPeak(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {
    if (!isInAbsorptionWindow(now, config)) return false

    val nearPeakBySlope = ctx.slope <= config.peakSlopeThreshold
    val nearPeakByAccel = ctx.acceleration <= config.peakAccelThreshold

    val fallingClearly = ctx.slope <= -0.8 && ctx.consistency >= config.minConsistency
    val iobAlreadyHigh = ctx.iobRatio >= 0.6

    // Klassiek piekcriterium OF duidelijke post-peak daling
    return nearPeakBySlope ||  nearPeakByAccel || (fallingClearly && iobAlreadyHigh)
}

private data class SafetyBlock(
    val active: Boolean,
    val reason: String
)

private fun postPeakLockout(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig
): SafetyBlock {
    // Alleen relevant na commit (absorption window)
    if (!isInAbsorptionWindow(now, config)) return SafetyBlock(false, "")

    // Rond/na piek: slope laag of accel negatief
    val nearPeak = (ctx.slope <= config.peakSlopeThreshold) || (ctx.acceleration <= config.peakAccelThreshold)

    // BG-afhankelijke IOB-lock drempel
    val dynamicIobThreshold =
        (0.30 + 0.07 * ctx.deltaToTarget)
            .coerceIn(0.30, 0.70)

    val enoughIob =
        ctx.iobRatio >= dynamicIobThreshold

    return if (nearPeak && enoughIob) {
        SafetyBlock(
            true,
            "POST-PEAK LOCKOUT (slope/accel + iob=${"%.2f".format(ctx.iobRatio)} >= dyn=${"%.2f".format(dynamicIobThreshold)})"
        )
    } else {
        SafetyBlock(false, "")
    }

}


private fun postTurnIobLock(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    config: FCLvNextConfig
): SafetyBlock {

    // Alleen relevant buiten meals
    if (mealSignal.state != MealState.NONE) {
        return SafetyBlock(false, "")
    }

    val turningDown =
        ctx.slope < 0.0 &&
            ctx.acceleration <= 0.0

    // BG-afhankelijke IOB-lock drempel
    val dynamicIobThreshold =
        (0.30 + 0.07 * ctx.deltaToTarget)
            .coerceIn(0.30, 0.70)

    val enoughIob =
        ctx.iobRatio >= dynamicIobThreshold

    val reliable =
        ctx.consistency >= config.minConsistency

    return if (turningDown && enoughIob && reliable) {
        SafetyBlock(
            true,
            "POST-TURN IOB LOCK (iob>=${"%.2f".format(dynamicIobThreshold)})"
        )
    } else {
        SafetyBlock(false, "")
    }
}



private fun hypoGuardBlock(
    ctx: FCLvNextContext
): SafetyBlock {
    // Simpele 60-min ballistic forecast (conservatief)
    val h = 1.0
    val predicted = ctx.input.bgNow + ctx.slope * h + 0.5 * ctx.acceleration * h * h

    return if (predicted <= 4.4) {
        SafetyBlock(true, "HYPO GUARD (pred60=${"%.2f".format(predicted)})")
    } else {
        SafetyBlock(false, "")
    }
}

private data class RescueSignal(
    val state: RescueState,
    val armed: Boolean,
    val confirmed: Boolean,
    val confidence: Double,
    val reason: String,
    val pred60: Double
)

/**
 * Soft inference: "rescue carbs likely"
 * Fase 1: alleen logging/labeling (geen dosing impact).
 */
private fun updateRescueDetection(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig,
    deliveredThisCycle: Double
): RescueSignal {

    val pred60 = predictBg60(ctx)

    // --- Tunable thresholds (start conservatief) ---
    val armPred60 = 4.6          // arm bij voorspelde hypo-risk
    val armBgNow = 5.2           // of al laag-ish
    val armSlope = -0.9          // stevige daling
    val minIobToCare = 0.25      // alleen als er iob aanwezig is (anders kan het "gewoon" dalen door geen carbs)

    val confirmMinMinutes = 8
    val confirmMaxMinutes = 30

    // Rebound kenmerken: van dalend naar duidelijk herstel
    val reboundAccelMin = 0.18
    val reboundSlopeMin = 0.25

    // “Geen insulin verklaart rebound”: totaal sinds armedAt moet heel klein zijn
    val maxDeliveredSinceArmed = 0.10  // U (totaal) — start strikt

    // Cooldown na confirm (zodat je niet elke cycle confirmed blijft loggen)
    val confirmCooldownMin = 45

    // --- helper: total delivered since armedAt ---
    fun deliveredSince(t0: DateTime?): Double {
        if (t0 == null) return 0.0
        var sum = 0.0
        for ((t, u) in deliveryHistory) {
            if (t.isAfter(t0) || t.isEqual(t0)) sum += u
        }
        return sum
    }

    // --- Reset rule: na confirm cooldown terug naar IDLE ---
    if (rescue.state == RescueState.CONFIRMED) {
        val since = minutesSince(rescue.lastConfirmAt, now)
        if (since >= confirmCooldownMin) {
            rescue = RescueDetectionContext() // reset alles
        }
    }

    // --- ARM criteria (hypo risk) ---
    val armByPred = pred60 <= armPred60
    val armByDynamics =
        (ctx.input.bgNow <= armBgNow && ctx.slope <= armSlope && ctx.iobRatio >= minIobToCare && ctx.consistency >= config.minConsistency)

    val shouldArm = (armByPred || armByDynamics) && ctx.consistency >= config.minConsistency

    when (rescue.state) {
        RescueState.IDLE -> {
            if (shouldArm) {
                rescue.state = RescueState.ARMED
                rescue.armedAt = now
                rescue.armedBg = ctx.input.bgNow
                rescue.armedPred60 = pred60
                rescue.armedSlope = ctx.slope
                rescue.armedAccel = ctx.acceleration
                rescue.armedIobRatio = ctx.iobRatio
                rescue.lastReason = "ARM: pred60=${"%.2f".format(pred60)} bg=${"%.2f".format(ctx.input.bgNow)} slope=${"%.2f".format(ctx.slope)} iobR=${"%.2f".format(ctx.iobRatio)}"
                rescue.confidence = 0.35
            }
        }

        RescueState.ARMED -> {
            val t0 = rescue.armedAt
            val dt = minutesSince(t0, now)

            // Als risico verdwijnt heel snel (bv sensor ruis) -> terug naar IDLE
            val riskGone = pred60 > 5.2 && ctx.slope > -0.2 && dt >= 10
            if (riskGone) {
                rescue = RescueDetectionContext()
            } else {
                // Confirm window + rebound + no extra insulin
                val inWindow = dt in confirmMinMinutes..confirmMaxMinutes
                val rebound = (ctx.acceleration >= reboundAccelMin && ctx.slope >= reboundSlopeMin)

                val deliveredTotalSince = deliveredSince(t0) + deliveredThisCycle
                val noInsulin = deliveredTotalSince <= maxDeliveredSinceArmed

                if (inWindow && rebound && noInsulin) {
                    rescue.state = RescueState.CONFIRMED
                    rescue.lastConfirmAt = now

                    // confidence bouwen (simpel)
                    val predSeverity = ((4.6 - rescue.armedPred60) / 1.0).coerceIn(0.0, 1.0) // lager pred60 => meer
                    val reboundStrength = ((ctx.acceleration - reboundAccelMin) / 0.25).coerceIn(0.0, 1.0)
                    val insulinClean = (1.0 - (deliveredTotalSince / maxDeliveredSinceArmed)).coerceIn(0.0, 1.0)

                    rescue.confidence = (0.45 * predSeverity + 0.35 * reboundStrength + 0.20 * insulinClean).coerceIn(0.0, 1.0)

                    rescue.lastReason =
                        "CONFIRM: dt=${dt}m pred60@arm=${"%.2f".format(rescue.armedPred60)} " +
                            "→ rebound slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                            "delivSince=${"%.2f".format(deliveredTotalSince)}U conf=${"%.2f".format(rescue.confidence)}"
                }
            }
        }

        RescueState.CONFIRMED -> {
            // niks: cooldown reset doet het werk
        }
    }

    return RescueSignal(
        state = rescue.state,
        armed = rescue.state == RescueState.ARMED,
        confirmed = rescue.state == RescueState.CONFIRMED,
        confidence = rescue.confidence,
        reason = rescue.lastReason,
        pred60 = pred60
    )
}


private fun predictBg60(ctx: FCLvNextContext): Double {
    val h = 1.0
    return ctx.input.bgNow + ctx.slope * h + 0.5 * ctx.acceleration * h * h
}




private fun smooth01(x: Double): Double {
    val t = x.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)   // smoothstep
}

private fun invSmooth01(x: Double): Double = 1.0 - smooth01(x)

private fun lerp(a: Double, b: Double, t: Double): Double =
    a + (b - a) * t.coerceIn(0.0, 1.0)

private data class EarlyDoseDecision(
    val active: Boolean,
    val stageToFire: Int,          // 0=none, 1=probe, 2=boost
    val confidence: Double,        // 0..1
    val targetU: Double,           // floor target for commandedDose
    val reason: String
)

private fun computeEarlyDoseDecision(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    trend: TrendDecision,
    trendConfirmedPersistent: Boolean, // <-- nieuw
    bgZone : BgZone,
    now: DateTime,
    config: FCLvNextConfig,
    tuning: FCLvNextProfileTuning
): EarlyDoseDecision {

    if (ctx.consistency < 0.45) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: low consistency")
    }

    if ((bgZone == BgZone.LOW || bgZone == BgZone.IN_RANGE) && ctx.iobRatio >= 0.55) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: low BG zone")
    }


    if (peak.state == PeakPredictionState.CONFIRMED) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: peak confirmed")
    }

    val slopeScore = smooth01((ctx.slope - 0.20) / (1.20 - 0.20))
    val accelScore = smooth01((ctx.acceleration - (-0.02)) / (0.15 - (-0.02)))
    val deltaScore = smooth01((ctx.deltaToTarget - 0.0) / 1.6)
    val consistScore = smooth01((ctx.consistency - 0.45) / 0.35)
    val iobRoom = invSmooth01((ctx.iobRatio - 0.20) / 0.50)

    val watchingBonus =
        if (peak.state == PeakPredictionState.WATCHING) 0.10 else 0.0

    val mealBonus = when (mealSignal.state) {
        MealState.CONFIRMED -> 0.18
        MealState.UNCERTAIN -> 0.10
        MealState.NONE -> 0.0
    }

    var conf =
        0.32 * slopeScore +
            0.30 * accelScore +
            0.18 * deltaScore +
            0.10 * consistScore +
            0.10 * iobRoom +
            watchingBonus +
            mealBonus

    val peakEscalation =
        if (peak.predictedPeak >= 12.5 &&
            ctx.iobRatio <= 0.45 &&
            ctx.consistency >= config.minConsistency
        ) tuning.earlyPeakEscalationBonus else 0.0

    conf += peakEscalation

    conf = conf.coerceIn(0.0, 1.0)

    val baseStage1Min = 0.28
// ✅ Stage2 eerder als de voorspelde piek groot is (timing fix, niet meer totaal)
    val stage2Min =
        if (peak.predictedPeak >= 16.0) 0.48 else 0.55

// jouw bestaande fast-carb versneller (blijft bestaan)
    val fastCarbStage1Mul =
        if (ctx.acceleration >= 0.35 && ctx.iobRatio <= 0.25) 0.75 else 1.0

// profiel beïnvloedt alleen timing (threshold)
    val dynamicStage1Min =
        (baseStage1Min * fastCarbStage1Mul * tuning.earlyStage1ThresholdMul)
            .coerceIn(0.12, 0.45)

    val minutesSinceLastFire =
        minutesSince(earlyDose.lastFireAt, now)

    val allowLarge = allowsLargeActions(trend.state, trendConfirmedPersistent)

// stageToFire:
    val stageToFire = when {
        earlyDose.stage == 0 && conf >= dynamicStage1Min -> 1
        earlyDose.stage == 1 && conf >= stage2Min &&
            minutesSinceLastFire >= 5 && allowLarge -> 2
        else -> 0
    }
    if (earlyDose.stage == 1 && conf >= stage2Min && minutesSinceLastFire >= 5 && !allowLarge) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: stage2 blocked (trend=${trend.state})")
    }

    if (stageToFire == 0) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: no fire")
    }

    val (minF, maxF) =
        if (stageToFire == 1) 0.40 to 0.70 else 0.55 to 0.90

    var factor = lerp(minF, maxF, conf)

    val iobPenalty = smooth01((ctx.iobRatio - 0.35) / 0.40)
    factor *= (1.0 - 0.35 * iobPenalty)
// iets meer iob demping als de piek te hoog is
 /*   val iobPenalty = smooth01((ctx.iobRatio - 0.30) / 0.40)
    factor *= (1.0 - 0.45 * iobPenalty)  */

    if (ctx.input.isNight) factor *= 0.88

    val targetU =
        (config.maxSMB * factor).coerceIn(0.0, config.maxSMB)

    return EarlyDoseDecision(
        active = true,
        stageToFire = stageToFire,
        confidence = conf,
        targetU = targetU,
        reason = "EARLY: stage=$stageToFire conf=${"%.2f".format(conf)}"
    )
}


private fun trajectoryDampingFactor(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    bgZone: BgZone,
    config: FCLvNextConfig
): Double {



    // Betrouwbaarheid
    if (ctx.consistency < config.minConsistency) return 1.0

    val delta = ctx.deltaToTarget        // mmol boven target
    val iobR  = ctx.iobRatio             // 0.. ~1+
    val slope = ctx.slope                // mmol/L/h
    val accel = ctx.acceleration         // mmol/L/h^2


    // 1) BG/delta: hoe hoger boven target, hoe minder remming
    //    delta=0 -> 0, delta>=6 -> 1
    val deltaScore = smooth01((delta - 0.0) / 6.0)

    // 2) IOB: hoe hoger, hoe meer remming
    //    iob<=0.35 -> ~0 rem, iob>=0.85 -> ~1 rem
    val iobPenalty = smooth01((iobR - 0.35) / (0.85 - 0.35))

    // 3) Slope: dalend/flat geeft remming, stijgend haalt remming weg
    //    slope<=-0.6 -> 1 rem, slope>=+1.0 -> 0 rem
    val slopePenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            invSmooth01((slope - (-0.8)) / (1.2 - (-0.8)))

        BgZone.MID ->
            invSmooth01((slope - (-0.6)) / (1.0 - (-0.6)))

        BgZone.IN_RANGE, BgZone.LOW ->
            invSmooth01((slope - (-0.2)) / (0.6 - (-0.2)))
    }

    // 4) Accel: negatief (afremmen/omkeren) geeft remming
    //    accel<=-0.10 -> 1 rem, accel>=+0.15 -> 0 rem
    val accelPenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            invSmooth01((accel - (-0.15)) / (0.20 - (-0.15)))

        BgZone.MID ->
            invSmooth01((accel - (-0.10)) / (0.15 - (-0.10)))

        BgZone.IN_RANGE, BgZone.LOW ->
            invSmooth01((accel - (-0.02)) / (0.08 - (-0.02)))
    }

    // 5) Meal: als we in meal staan, minder streng (want stijging kan “legit” zijn)
    val mealRelax = when (mealSignal.state) {
        MealState.NONE -> 1.0
        MealState.UNCERTAIN -> 0.75
        MealState.CONFIRMED -> 0.55
    }

    // Combineer:
    // - Penalties versterken elkaar
    // - deltaScore werkt “tegen” penalties in (hoge delta laat meer toe)
    val combinedPenalty =
        (0.55 * iobPenalty + 0.25 * slopePenalty + 0.20 * accelPenalty)
            .coerceIn(0.0, 1.0)

    // Baseline factor: 1 - penalty
    var factor = (1.0 - combinedPenalty).coerceIn(0.0, 1.0)

    // Delta laat factor weer oplopen (bij hoge delta minder rem)
    // deltaScore=0 -> geen uplift, deltaScore=1 -> uplift tot +0.35
    factor = (factor + 0.35 * deltaScore).coerceIn(0.0, 1.0)

    // Meal relax (vermindert penalties) -> factor omhoog
    factor = (factor / mealRelax).coerceAtMost(1.0)

    // Extra bescherming: hoge IOB + geen meal + geen echte stijging → factor sterk omlaag
    if (mealSignal.state == MealState.NONE &&
        ctx.iobRatio >= 0.65 &&
        ctx.slope < 0.8
    ) {
        factor *= 0.25
    }

    // 🔒 LOW-BG HARD CLAMP
    if (bgZone == BgZone.LOW && ctx.slope <= 0.0) {
        return 0.0
    }

    return factor
}

private fun isEarlyProtectionActive(
    earlyStage: Int,
    ctx: FCLvNextContext,
    peak: PeakEstimate
): Boolean {

    if (earlyStage <= 0) return false

    // ❗ ZODRA AFREM MEN BEGINT → early protection UIT
    if (ctx.acceleration < 0.0) return false

    if (peak.state == PeakPredictionState.CONFIRMED) return false

    return true
}


private fun shouldHardBlockTrajectory(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    earlyStage: Int,
    peak: PeakEstimate
): Boolean {

    // ✅ Extra harde regel: duidelijke omkeer + al redelijk IOB -> meteen blokkeren
    if (ctx.acceleration <= -0.10 && ctx.iobRatio >= 0.35 && ctx.consistency >= 0.45) {
        return true
    }
    // early bescherming alleen zolang we nog niet afremmen / piek hebben
    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false

    // nooit hard block bij meal
    if (mealSignal.state != MealState.NONE) return false

    val highIob = ctx.iobRatio >= 0.70
    val notReallyRising = ctx.slope < 0.6
    val decelerating = ctx.acceleration <= -0.05
    val reliable = ctx.consistency >= 0.5

    return highIob && notReallyRising && decelerating && reliable
}

private fun shouldBlockMicroCorrections(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peakCategory: PeakCategory,
    earlyStage: Int,
    peak: PeakEstimate,
    config: FCLvNextConfig
): Boolean {

    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false


    // Alleen voor "geen-meal" correcties
    if (mealSignal.state != MealState.NONE) return false

    // Als het echt een meal/high episode is, niet blokkeren
    if (peakCategory >= PeakCategory.MEAL) return false

    val fallingOrFlat =
        ctx.slope <= config.correctionHoldSlopeMax &&   // bv <= -0.20
            ctx.acceleration <= config.correctionHoldAccelMax && // bv <= 0.05
            ctx.consistency >= config.minConsistency

    // Als BG nog maar weinig boven target zit -> zeker wachten
    val notFarAboveTarget =
        ctx.deltaToTarget <= config.correctionHoldDeltaMax  // bv <= 1.5

    return fallingOrFlat && notFarAboveTarget
}

/**
 * Re-entry: tweede gang / dessert.
 * Alleen toestaan als:
 * - genoeg tijd sinds commit
 * - én duidelijke nieuwe stijging (slope/accel/delta)
 * - én reentry cooldown gerespecteerd
 */
private fun isReentrySignal(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {
    val sinceCommit = minutesSince(lastCommitAt, now)
    if (sinceCommit < config.reentryMinMinutesSinceCommit) return false

    val sinceReentry = minutesSince(lastReentryCommitAt, now)
    if (sinceReentry < config.reentryCooldownMinutes) return false

    val rising = ctx.slope >= config.reentrySlopeMin
    val accelerating = ctx.acceleration >= config.reentryAccelMin
    val aboveTarget = ctx.deltaToTarget >= config.reentryDeltaMin
    val reliable = ctx.consistency >= config.minConsistency

    return reliable && aboveTarget && rising && accelerating
}


class FCLvNext(
    private val preferences: Preferences
) {

    private val coreParamLogger =
        FCLvNextParameterLogger(
            fileName = "FCLvNext_CoreParameters.csv"
        ) {
            FCLvNextCoreParameterSnapshot.collect(preferences)
        }

    private val profileParamLogger =
        FCLvNextParameterLogger(
            fileName = "FCLvNext_ProfileParameters.csv"
        ) {
            FCLvNextProfileParameterSnapshot.collect(preferences)
        }

    private val learningEpisodes = FCLvNextLearningEpisodeManager()

    private val learningAdvisor = FCLvNextLearningAdvisor()
    fun getLearningStatus(isNight: Boolean): String {
        return learningAdvisor.getLearningStatus(isNight)
    }

    private val learningPersistence = FCLvNextLearningPersistence(preferences)

    init {
        learningPersistence.loadInto(learningAdvisor)
    }


    private fun buildContext(input: FCLvNextInput): FCLvNextContext {
        val filteredHistory = FCLvNextBgFilter.ewma(
            data = input.bgHistory,
            alpha = preferences.get(DoubleKey.fcl_vnext_bg_smoothing_alpha)
        )

        val points = filteredHistory.map { (t, bg) ->
            FCLvNextTrends.BGPoint(t, bg)
        }

        val trends = FCLvNextTrends.calculateTrends(points)

        val iobRatio = if (input.maxIOB > 0.0) {
            (input.currentIOB / input.maxIOB).coerceIn(0.0, 1.5)
        } else 0.0

        return FCLvNextContext(
            input = input,
            slope = trends.firstDerivative,
            acceleration = trends.secondDerivative,
            consistency = trends.consistency,
            iobRatio = iobRatio,
            deltaToTarget = input.bgNow - input.targetBG
        )
    }

    fun getAdvice(input: FCLvNextInput): FCLvNextAdvice {

        // ─────────────────────────────────────────────
        // 1️⃣ Config & context (trends, IOB, delta)
        // ─────────────────────────────────────────────
        val config = loadFCLvNextConfig(preferences, input.isNight)
        val ctx = buildContext(input)
        val profileName = preferences.get(StringKey.fcl_vnext_profile)
        val profile = runCatching { FCLvNextProfile.valueOf(profileName) }
            .getOrElse { FCLvNextProfile.BALANCED }
        val tuning = FCLvNextProfiles.tuning(profile)




        val bgZone = computeBgZone(ctx).name   // "LOW/IN_RANGE/MID/HIGH/EXTREME"

        val status = StringBuilder()
        status.append("PROFILE=$profile\n")

        // ─────────────────────────────────────────────
        // 2️⃣ Persistent HIGH BG detectie
        //     (los van maaltijdlogica)
        // ─────────────────────────────────────────────
    //    val persistentActive = isPersistentHighBG(ctx, config)
    //    status.append("Persistent=${if (persistentActive) "YES" else "NO"}\n")

        // ─────────────────────────────────────────────
        // 3️⃣ Energie-model (positie + snelheid + versnelling)
        // ─────────────────────────────────────────────

        var energy = calculateEnergy(ctx, config)

        // ─────────────────────────────────────────────
       // 🔒 ENERGY EXHAUSTION GATE (post-rise hard stop)
       // ─────────────────────────────────────────────
        val energyExhausted =
            ctx.deltaToTarget >= 4.5 &&            // duidelijk boven target
                ctx.acceleration in -0.05..0.05 &&             // versnelling vrijwel weg
                ctx.slope >= 0.8 &&                     // slope hoog door historie
                ctx.iobRatio >= 0.55 &&                 // al voldoende insulin aan boord
                ctx.consistency >= config.minConsistency

        if (energyExhausted) {
            status.append(
                "ENERGY EXHAUSTED: slope=${"%.2f".format(ctx.slope)} " +
                    "accel=${"%.2f".format(ctx.acceleration)} " +
                    "iob=${"%.2f".format(ctx.iobRatio)} → energy=0\n"
            )
            energy = 0.0
        }

        val stagnationBoost =
            calculateStagnationBoost(ctx, config)

        val energyTotal = energy + stagnationBoost

        status.append(
            "StagnationBoost=${"%.2f".format(stagnationBoost)}\n"
        )


        // ─────────────────────────────────────────────
        // 4️⃣ Ruwe dosis uit energie
        // ─────────────────────────────────────────────
        val rawDose = energyToInsulin(
            energy = energyTotal,
            effectiveISF = input.effectiveISF,
            config = config
        )
        status.append("RawDose=${"%.2f".format(rawDose)}U\n")

        // ─────────────────────────────────────────────
        // 5️⃣ Beslissingslaag (hard stop / force / soft allow)
        // ─────────────────────────────────────────────
        val decision = decide(ctx)

        val decidedDose = when {
            !decision.allowed -> 0.0
            decision.force -> rawDose
            else -> rawDose * decision.dampening
        }

        status.append(
            "Decision=${decision.reason} → ${"%.2f".format(decidedDose)}U\n"
        )

        // ─────────────────────────────────────────────
        // 6️⃣ IOB-remming (centraal, altijd toepassen)
        // ─────────────────────────────────────────────
        // ─────────────────────────────────────────────
        // 6a️⃣ Peak prediction (voor IOB-remming)
        // ─────────────────────────────────────────────

        val now = DateTime.now()
        val mealSignal = detectMealSignal(ctx, config)
        // Peak-estimator mag ook actief worden zonder mealSignal,
        // maar niet bij lage betrouwbaarheid
        if (ctx.consistency < config.minConsistency) {
            peakEstimator.active = false
        }
        val peak = updatePeakEstimate(config, ctx, mealSignal, now)
        val peakState = peak.state
        val predictedPeak = peak.predictedPeak
        val peakCategory = classifyPeak(predictedPeak)
        val allowPrePeakBundling =
            peakState == PeakPredictionState.WATCHING

        val trend = classifyTrendState(ctx, config)
        status.append(trend.reason + "\n")

// ── TREND PERSISTENCE (Stap 2) ──
        when (trend.state) {
            TrendState.RISING_CONFIRMED -> {
                trendConfirmCounter++
            }
            TrendState.RISING_WEAK -> {
                trendConfirmCounter = maxOf(trendConfirmCounter - 1, 0)
            }
            TrendState.NONE -> {
                trendConfirmCounter = 0
            }
        }

        val effectiveTrendConfirmCycles =
            (config.trendConfirmCycles + tuning.trendConfirmCyclesDelta).coerceIn(1, 6)

        val trendConfirmedPersistent =
            trendConfirmCounter >= effectiveTrendConfirmCycles

        status.append(
            "TrendPersistence=${trendConfirmCounter}/${effectiveTrendConfirmCycles} " +
                "persistent=${trendConfirmedPersistent}\n"
        )



        status.append(
            "PeakEstimate=${peak.state} " +
                "pred=${"%.2f".format(peak.predictedPeak)} " +
                "cat=$peakCategory " +
                "band=${peak.peakBand} " +
                "maxSlope=${"%.2f".format(peak.maxSlope)} " +
                "mom=${"%.2f".format(peak.momentum)}\n"
        )

        // ✅ Pre-peak commit window: helpt IOB eerder opbouwen vóór de top
        val prePeakCommitWindow =
            peak.state == PeakPredictionState.WATCHING &&
                peak.predictedPeak >= 15.0 &&
                ctx.consistency >= 0.55 &&
                ctx.iobRatio <= 0.50 &&
                ctx.acceleration >= 0.00    // niet tijdens afremmen

        status.append("PrePeakCommitWindow=${if (prePeakCommitWindow) "YES" else "NO"}\n")


        val peakIobBoost = when (peakCategory) {
            PeakCategory.EXTREME -> 1.55
            PeakCategory.HIGH    -> 1.40
            PeakCategory.MEAL    -> 1.25
            PeakCategory.MILD    -> 1.10
            PeakCategory.NONE    -> 1.00
        }

        val boostedIobRatio =
            (ctx.iobRatio / peakIobBoost).coerceAtLeast(0.0)

        val iobPower = if (input.isNight) 2.8 else 2.2
        val iobFactor = iobDampingFactor(
            iobRatio = boostedIobRatio,
            config = config,
            power = iobPower
        )

        val commitIobFactor = iobDampingFactor(
            iobRatio = ctx.iobRatio,
            config = config,
            power = config.commitIobPower   // NIEUW, milder
        )


        var finalDose = (decidedDose * iobFactor).coerceAtLeast(0.0)

        if (ctx.acceleration > 0.2 && ctx.iobRatio >= 0.75) {
            status.append("RISING IOB CAP → finalDose limited\n")
            finalDose = minOf(finalDose, 0.6 * config.maxSMB)
        }


        val zoneEnum = computeBgZone(ctx)
        val accessLevel = computeDoseAccessLevel(ctx, zoneEnum)

        status.append("DoseAccess=$accessLevel\n")

// Universele caps als fractie van maxSMB
        val microCap = maxOf(0.05, 0.10 * config.maxSMB)   // min 0.05U
        val smallCap = maxOf(0.10, 0.30 * config.maxSMB)   // min 0.10U

        val accessCap = when (accessLevel) {
            DoseAccessLevel.BLOCKED -> 0.0
            DoseAccessLevel.MICRO_ONLY -> microCap
            DoseAccessLevel.SMALL -> smallCap
            DoseAccessLevel.NORMAL -> Double.POSITIVE_INFINITY
        }

        when (accessLevel) {

            DoseAccessLevel.BLOCKED -> {
                status.append("ACCESS BLOCKED → finalDose=0\n")
                finalDose = 0.0
            }

            DoseAccessLevel.MICRO_ONLY -> {
                if (finalDose > microCap) {
                    status.append("ACCESS MICRO cap ${"%.2f".format(microCap)}\n")
                    finalDose = microCap
                }
            }

            DoseAccessLevel.SMALL -> {
                if (finalDose > smallCap) {
                    status.append("ACCESS SMALL cap ${"%.2f".format(smallCap)}\n")
                    finalDose = smallCap
                }
            }

            DoseAccessLevel.NORMAL -> {
                // geen beperking
            }
        }


// ─────────────────────────────────────────────
// 🚀 EARLY DOSE CONTROLLER (move earlier in pipeline)
// ─────────────────────────────────────────────


        val early = computeEarlyDoseDecision(
            ctx = ctx,
            mealSignal = mealSignal,
            peak = peak,
            trend = trend,
            trendConfirmedPersistent = trendConfirmedPersistent,
            bgZone = zoneEnum,
            now = now,
            config = config,
            tuning = tuning
        )

        status.append(early.reason + "\n")

// candidate stage (zodat micro-hold early niet per ongeluk blokkeert)
        val earlyStageCandidate = maxOf(earlyDose.stage, early.stageToFire)

// ── Micro-correction hold: niet drip-feeden als BG al daalt/vlak is ──
        if (shouldBlockMicroCorrections(
                ctx,
                mealSignal,
                peakCategory,
                earlyStageCandidate,
                peak,
                config
            )
        ) {
            status.append(
                "HoldCorrections: slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                    "delta=${"%.2f".format(ctx.deltaToTarget)} → finalDose=0\n"
            )
            finalDose = 0.0
        }

// ── Trajectory damping: continu remmen o.b.v. BG / IOB / slope / accel ──
        if (shouldHardBlockTrajectory(
                ctx,
                mealSignal,
                earlyStageCandidate,
                peak
            )
        ) {
            status.append("Trajectory HARD BLOCK → finalDose=0\n")
            finalDose = 0.0
        } else {
         //   val zoneEnum = computeBgZone(ctx)
            val trajFactor = trajectoryDampingFactor(ctx, mealSignal, zoneEnum, config)
            val before = finalDose
            finalDose *= trajFactor
            status.append(
                "TrajectoryFactor=${"%.2f".format(trajFactor)} " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }
// ===============================================================================


        if (early.active && early.targetU > accessCap) {
            status.append("EARLY capped by ACCESS (${accessLevel})\n")
        }
 // ===============================================================================

// Apply early floor AFTER dampers (maar vóór cap/commit)
// ✅ NIET toepassen als we al aan het afremmen zijn (accel < 0)
        if (early.active && early.targetU > 0.0 && ctx.acceleration >= 0.0) {

            val cappedEarly = minOf(early.targetU, accessCap)

            if (cappedEarly < early.targetU) {
                status.append(
                    "EARLY capped by ACCESS ($accessLevel): " +
                        "${"%.2f".format(early.targetU)}→${"%.2f".format(cappedEarly)}U\n"
                )
            }

            val before = finalDose
            finalDose = maxOf(finalDose, cappedEarly)

            earlyDose.stage = maxOf(earlyDose.stage, early.stageToFire)
            earlyDose.lastFireAt = now
            earlyDose.lastConfidence = early.confidence

            status.append(
                "EARLY FLOOR: ${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        } else if (early.active && early.targetU > 0.0 && ctx.acceleration < 0.0) {
            status.append("EARLY FLOOR skipped (accel<0)\n")
        }

        // ─────────────────────────────────────────────
       // 🟦 PERSISTENT CORRECTION LOOP (dag + nacht)
       // ─────────────────────────────────────────────

        val persistResult = persistCtrl.tickAndMaybeFire(
            tsMillis = now.millis,
            bgMmol = ctx.input.bgNow,
            targetMmol = ctx.input.targetBG,
            deltaToTarget = ctx.deltaToTarget,
            slope = ctx.slope,
            accel = ctx.acceleration,
            consistency = ctx.consistency,
            iob = ctx.input.currentIOB,
            iobRatio = ctx.iobRatio,
            maxBolusU = config.maxSMB,

            minDeltaToTarget = if (ctx.input.isNight) 1.7 else 1.5,
            stableSlopeAbs = 0.25,
            stableAccelAbs = 0.06,
            minConsistency = 0.45,
            confirmCycles = 2,

            minDoseU = 0.05,
            iobRatioHardStop = 0.45
        )


        if (persistResult.active) {
            status.append("PERSIST: ${persistResult.reason}\n")

            if (persistResult.fired) {
                // 🔑 Persistent neemt hier expliciet de leiding
                finalDose = persistResult.doseU
                status.append(
                    "PERSIST APPLY: finalDose=${"%.2f".format(finalDose)}U\n"
                )
            } else {
                // cooldown / hold → bewust niets doen
                finalDose = 0.0
                status.append("PERSIST HOLD: finalDose=0\n")
            }
        }

        // 🔒 markeer dat persistent de leiding heeft
        val persistentOverrideActive = persistResult.active

        if (persistentOverrideActive) {
            status.append(
                "PERSIST MODE: HARD (cooldown=${persistResult.cooldownLeft})\n"
            )
        }




        // ─────────────────────────────────────────────
       // ⚡ EARLY CONFIRM IMPULSE (bridges early → commit)
      // ─────────────────────────────────────────────
        val earlyConfirm =
            !earlyConfirmDone &&
                earlyDose.stage >= 2 &&                 // we hadden al een early boost
                allowsLargeActions(trend.state, trendConfirmedPersistent) &&     // <-- nieuw: harde gate
                ctx.slope >= 1.0 &&                     // duidelijke stijging
                ctx.acceleration >= 0.20 &&             // versnelling bevestigd
                ctx.deltaToTarget >= 2.0 &&             // echt boven target
                ctx.iobRatio <= 0.45 &&                 // nog ruimte
                ctx.consistency >= config.minConsistency &&
                peak.state != PeakPredictionState.CONFIRMED

        if (earlyConfirm) {
            val impulse =
                (0.6 * config.maxSMB)
                    .coerceAtLeast(0.3)
                    .coerceAtMost(config.maxSMB)

            val before = finalDose
            finalDose = maxOf(finalDose, impulse)

            earlyConfirmDone = true

            status.append(
                "EARLY-CONFIRM IMPULSE: slope=${"%.2f".format(ctx.slope)} " +
                    "accel=${"%.2f".format(ctx.acceleration)} → " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }


        // ─────────────────────────────────────────────
        // 8️⃣ Absolute max SMB cap
        // ─────────────────────────────────────────────
        if (finalDose > config.maxSMB) {
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(config.maxSMB)}U\n"
            )
            finalDose = config.maxSMB
        }



// ─────────────────────────────────────────────
// 9️⃣ Meal detectie & commit/observe + peak suppression + re-entry
// ─────────────────────────────────────────────

        val commitAllowed = canCommitNow(now, config)

        status.append(mealSignal.reason + "\n")
        status.append("CommitAllowed=${if (commitAllowed) "YES" else "NO"}\n")

        var commandedDose =
            if (persistentOverrideActive) {
                // 🔒 persistent beslist volledig
                finalDose
            } else {
                finalDose
            }

        // ── Anti-drip: kleine correcties niet elke cyclus ──
        if (commandedDose > 0.0 &&
            commandedDose <= config.smallCorrectionMaxU &&
            mealSignal.state == MealState.NONE &&
            earlyDose.stage == 0   // ⬅️ EARLY DOSE UITZONDEREN
        ) {
            val minutesSinceSmall = minutesSince(lastSmallCorrectionAt, now)
            if (minutesSinceSmall < config.smallCorrectionCooldownMinutes) {
                status.append("SmallCorrectionCooldown: ${minutesSinceSmall}m < ${config.smallCorrectionCooldownMinutes}m → dose=0\n")
                commandedDose = 0.0
            } else {
                lastSmallCorrectionAt = now
            }
        }


// 9a) Peak/absorption suppression: stop of reduce rond/na piek
        val suppressForPeak =
            shouldSuppressForPeak(ctx, now, config)
        if (suppressForPeak) {
            val reduced = (finalDose * config.absorptionDoseFactor).coerceAtLeast(0.0)
            commandedDose = reduced
            status.append(
                "ABSORBING/PEAK: suppression active " +
                    "(slope=${"%.2f".format(ctx.slope)}, accel=${"%.2f".format(ctx.acceleration)}) " +
                    "→ ${"%.2f".format(commandedDose)}U\n"
            )
        }

// 9b) Re-entry: tweede gang (mag suppression overrulen als het écht weer stijgt)
        val reentry = isReentrySignal(ctx, now, config)
        if (reentry) {
            // nieuw segment binnen episode
            prePeakImpulseDone = false
            lastSegmentAt = now
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false

            status.append("SEGMENT: re-entry → new impulse window\n")
        }
// 🔒 POST-PEAK COMMIT BLOCK: nooit committen na curve-omkeer
        val postPeakCommitBlocked =
                ctx.acceleration < -0.05 &&
                ctx.iobRatio >= 0.30 &&
                !reentry &&                      // re-entry mag dit overrulen
                ctx.consistency >= config.minConsistency

// 9c) Commit logic (alleen als we niet in peak-suppress zitten, OF als re-entry waar is)
        val allowCommitPath =
            ((!suppressForPeak) || reentry) &&
                !postPeakCommitBlocked

        if (postPeakCommitBlocked) {
            status.append("POST-PEAK: accel<0 → commit blocked\n")
        }

        val fastCarbOverride =
            tuning.fastCarbOverrideEnabled &&
                peak.predictedPeak >= 12.0 &&
                ctx.slope >= 1.5 &&
                ctx.acceleration >= 0.25 &&
                ctx.consistency >= config.minConsistency

        val effectiveMeal =
            mealSignal.state != MealState.NONE || fastCarbOverride

        if (fastCarbOverride) {
            status.append("FAST-CARB override: effectiveMeal=TRUE\n")
        }


        if (allowCommitPath && effectiveMeal) {

            // ✅ Commit boost mag óók via pre-peak window (timing fix)
            val allowCommitBoost =
                allowsLargeActions(trend.state, trendConfirmedPersistent) || prePeakCommitWindow

            if (!allowCommitBoost) {
                status.append("COMMIT gated (trend=${trend.state}, prePeakWindow=${prePeakCommitWindow})\n")
            } else if (prePeakCommitWindow && !trendConfirmedPersistent) {
                status.append("COMMIT boost via PRE-PEAK window (trend not persistent yet)\n")
            }


            val effectiveCommitAllowed =
                if (reentry) true else commitAllowed

            status.append(
                "EffectiveCommitAllowed=${if (effectiveCommitAllowed) "YES" else "NO"}\n"
            )

            if (effectiveCommitAllowed) {

                val baseFraction = computeCommitFraction(mealSignal, config)
                val zoneFactor = commitFractionZoneFactor(zoneEnum)
                val fraction = (baseFraction * zoneFactor).coerceIn(0.0, 1.0)

                status.append(
                    "CommitFraction: base=${"%.2f".format(baseFraction)} zoneFactor=${"%.2f".format(zoneFactor)} → ${"%.2f".format(fraction)}\n"
                )

                val commitAccessOk = (accessLevel == DoseAccessLevel.NORMAL)

                if (!commitAccessOk) {
                    status.append("COMMIT limited by ACCESS ($accessLevel)\n")
                }

                val prePeakMul = if (prePeakCommitWindow) 0.85 else 1.0

                val commitDose =
                    if (allowCommitBoost && commitAccessOk)
                        (config.maxSMB * fraction * commitIobFactor * prePeakMul)
                            .coerceAtMost(config.maxSMB)
                    else
                        0.0


                val committedDose =
                    if (peakCategory >= PeakCategory.HIGH)
                        maxOf(finalDose, commitDose * 1.15)
                    else
                        maxOf(finalDose, commitDose)

                if (committedDose >= config.minCommitDose) {

                    commandedDose = committedDose

                    lastCommitAt = now
                    lastCommitDose = committedDose
                    lastCommitReason =
                        "${mealSignal.state} frac=${"%.2f".format(fraction)}"

                    if (reentry) {
                        lastReentryCommitAt = now
                        status.append("RE-ENTRY COMMIT set\n")
                    }

                    status.append(
                        "COMMIT ${"%.2f".format(committedDose)}U " +
                            "(${mealSignal.state}, conf=${"%.2f".format(mealSignal.confidence)})\n"
                    )

                } else {
                    status.append("COMMIT skipped (below minCommitDose)\n")
                }

            } else {
                status.append("OBSERVE (commit cooldown)\n")
            }
        }

// ─────────────────────────────────────────────
// HARD SAFETY BLOCKS (final gate before delivery)
// ─────────────────────────────────────────────
        val hypoBlock = hypoGuardBlock(ctx)
        if (hypoBlock.active) {
            status.append(hypoBlock.reason + " → commandedDose=0\n")
            commandedDose = 0.0
        }

        val postPeakBlock = postPeakLockout(ctx, now, config)
        if (postPeakBlock.active) {
            status.append(postPeakBlock.reason + " → commandedDose=0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }

        val postTurnBlock = postTurnIobLock(ctx, mealSignal, config)
        if (postTurnBlock.active) {
            status.append(postTurnBlock.reason + " → commandedDose=0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }




        // ─────────────────────────────────────────────
        // 🔟 Execution: SMB / hybride bolus + basaal
        // ─────────────────────────────────────────────
        val execution = executeDelivery(
            dose = commandedDose,
            hybridPercentage = config.hybridPercentage,
            cycleMinutes = config.deliveryCycleMinutes,
            maxTempBasalRate = config.maxTempBasalRate
        )
        val deliveredNow = execution.deliveredTotal
        if (deliveredNow > 0.0) {
            deliveryHistory.addFirst(DateTime.now() to deliveredNow)
            while (deliveryHistory.size > MAX_DELIVERY_HISTORY) {
                deliveryHistory.removeLast()
            }
        }

        status.append(
            "DELIVERY: dose=${"%.2f".format(commandedDose)}U " +
                "basal=${"%.2f".format(execution.basalRate)}U/h " +
                "bolus=${"%.2f".format(execution.bolus)}U " +
                "(${config.deliveryCycleMinutes}m)\n"
        )

        val shouldDeliver =
            if (persistentOverrideActive) {
                true   // 🔒 AAPS mag NIETS beslissen
            } else {
                execution.bolus >= 0.05 || execution.basalRate > 0.0
            }

        val rescueSignal = updateRescueDetection(
            ctx = ctx,
            now = now,
            config = config,
            deliveredThisCycle = deliveredNow
        )

        status.append("RESCUE: state=${rescueSignal.state} conf=${"%.2f".format(rescueSignal.confidence)} pred60=${"%.2f".format(rescueSignal.pred60)}\n")
        if (rescueSignal.armed || rescueSignal.confirmed) {
            status.append("RESCUE: ${rescueSignal.reason}\n")
        }

// episode start (primitives)
        learningEpisodes.maybeStartEpisode(
            ctx = ctx,
            peakActive = peakEstimator.active,
            earlyStage = earlyDose.stage,
            mealActive = (mealSignal.state != MealState.NONE),
            now = now
        )

// episode update (primitives)
        learningEpisodes.updateEpisode(
            ctx = ctx,
            earlyStage = earlyDose.stage,
            deliveredNow = deliveredNow,
            rescueConfirmed = rescueSignal.confirmed
        )

        if (
            learningEpisodes.shouldEndEpisode(
                ctx = ctx,
                peakActive = peakEstimator.active,
                lastCommitAt = lastCommitAt,
                now = now,
                config = config
            )
        ) {
            val outcome = learningEpisodes.closeEpisode(
                ctx = ctx,
                predictedPeak = peak.predictedPeak,
                peakBand = peak.peakBand,
                config = config
            )

            // Fase 2: advisor voedden
            learningAdvisor.onEpisodeOutcome(
                outcome = outcome,
                isNight = input.isNight,
                peakBand = peak.peakBand,
                rescueConfirmed = rescueSignal.confirmed,
                mealActive = (mealSignal.state != MealState.NONE)
            )
            // ✅ persist learning na episode close
            learningPersistence.saveFrom(learningAdvisor)

        }



        // ─────────────────────────────────────────────
        // CSV logging (analyse / tuning)
        // ─────────────────────────────────────────────
        FCLvNextCsvLogger.log(
            isNight = input.isNight,
            bg = input.bgNow,
            target = input.targetBG,
            
            // trends
            slope = ctx.slope,
            accel = ctx.acceleration,
            consistency = ctx.consistency,

            // IOB
            iob = input.currentIOB,
            iobRatio = ctx.iobRatio,
            bgZone = bgZone,
            doseAccess = accessLevel.name,

            // model
            effectiveISF = input.effectiveISF,
            gain = config.gain,
            energyBase = energy,
            energyTotal = energyTotal,
            // 🆕 NASLEEP / ADVISOR
            stagnationActive = stagnationBoost > 0.0,
            stagnationBoost = stagnationBoost,
            stagnationAccel = ctx.acceleration,
            stagnationAccelLimit = config.stagnationAccelMaxAbs,

            rawDose = rawDose,
            iobFactor = iobFactor,


            // dosing
            finalDose = finalDose,
            deliveredTotal = execution.deliveredTotal,
            bolus = execution.bolus,
            basalRate = execution.basalRate,
            shouldDeliver = shouldDeliver,

            // decision
            decisionReason = decision.reason,

            // ── NEW: meal / phase / advisor support ──
            minutesSinceCommit =
                if (lastCommitAt != null)
                    org.joda.time.Minutes.minutesBetween(lastCommitAt, DateTime.now()).minutes
                else
                    -1,

            suppressForPeak = suppressForPeak,
            absorptionActive = isInAbsorptionWindow(now, config),
            reentrySignal = reentry,

            mealState = mealSignal.state.name,
            commitFraction =
                if (mealSignal.state != MealState.NONE)
                    computeCommitFraction(mealSignal, config)
                else
                    0.0,

            peakState = peakState.name,
            predictedPeak = predictedPeak,
            peakIobBoost = peakIobBoost,
            effectiveIobRatio = boostedIobRatio,
            peakBand = peak.peakBand,
            peakMaxSlope = peak.maxSlope,
            peakMomentum = peak.momentum,
            peakRiseSinceStart = peak.riseSinceStart,
            peakEpisodeActive = peakEstimator.active,

            normalDose = finalDose,
            commandedDose = commandedDose,

            earlyStage = earlyDose.stage,
            earlyConfidence = earlyDose.lastConfidence,
            earlyTargetU = early.targetU,

            pred60 = rescueSignal.pred60,
            rescueState = rescueSignal.state.name,
            rescueConfidence = rescueSignal.confidence,
            rescueReason = rescueSignal.reason,


        )

        // ─────────────────────────────────────────────
       // Parameter snapshot logging (laagfrequent)
       // ─────────────────────────────────────────────
        coreParamLogger.maybeLog()
        profileParamLogger.maybeLog()

        val learningAdvice = learningAdvisor.getAdvice(input.isNight)
        if (learningAdvice.isNotEmpty()) {
            status.append("LEARNING ADVICE:\n")
            learningAdvice
                .sortedByDescending { it.confidence }
                .take(4)
                .forEach { a ->
                    val dir = if (a.direction > 0) "↑" else "↓"
                    status.append(
                        " - ${a.parameter} $dir conf=${"%.2f".format(a.confidence)} n=${a.evidenceCount}\n"
                    )
                }
        }

        // ─────────────────────────────────────────────
       // PROFILE ADVICE (fase 2 – adviserend)
       // ─────────────────────────────────────────────
        val profileAdvice = learningAdvisor.getProfileAdvice()
        if (profileAdvice != null) {
            status.append(
                "PROFILE ADVICE: ${profileAdvice.recommended} " +
                    "conf=${"%.2f".format(profileAdvice.confidence)} " +
                    "n=${profileAdvice.evidenceCount}\n"
            )
            status.append("PROFILE REASON: ${profileAdvice.reason}\n")
        }



        // ─────────────────────────────────────────────
        // RETURN
        // ─────────────────────────────────────────────
        return FCLvNextAdvice(
            bolusAmount = execution.bolus,
            basalRate = execution.basalRate,
            shouldDeliver = shouldDeliver,
            effectiveISF = input.effectiveISF,
            targetAdjustment = 0.0,
            statusText = status.toString()
        )
    }


}


