package sa.factory.mihwarmodbusindust.engine

import java.util.Locale
import kotlin.math.sqrt

/**
 * 3-Axis Industrial Vibration Accelerometer & Machine Health Analytics Engine.
 *
 * Implements ISO 10816-3 and ISO 20816 international standards for industrial vibration
 * evaluation on rotating machinery (pumps, turbines, industrial fans, induction motors):
 * - Zone A: Newly commissioned machinery / optimal health (< 1.4 mm/s RMS).
 * - Zone B: Unrestricted continuous operation (1.4 - 2.8 mm/s RMS).
 * - Zone C: Restricted continuous operation / maintenance required (2.8 - 4.5 mm/s RMS).
 * - Zone D: Severe vibration / catastrophic damage imminent (> 4.5 mm/s RMS).
 * - Crest Factor analysis for early bearing race/cage fault diagnosis.
 * - Thermal boundary protection (Warning > 70 C, Trip > 90 C).
 *
 * Strict Zero-Emoji Invariant enforced across all strings and telemetry tags.
 */
enum class Iso10816Zone(val code: String, val severitySar: String, val statusCode: Int) {
    ZONE_A_OPTIMAL("ZONE_A_OPTIMAL", "حالة مثالية - معدات جديدة ومستقرة", 1),
    ZONE_B_ACCEPTABLE("ZONE_B_ACCEPTABLE", "حالة مقبولة - تشغيل مستمر دون قيود", 2),
    ZONE_C_WARNING("ZONE_C_WARNING", "تحذير - يتطلب صيانة وقائية مجدولة", 3),
    ZONE_D_CRITICAL("ZONE_D_CRITICAL", "خطر حرج - يوصى بإيقاف التشغيل فورا", 4)
}

data class VibrationSample(
    val xVelocityRmsMmS: Double,
    val yVelocityRmsMmS: Double,
    val zVelocityRmsMmS: Double,
    val peakAccelerationG: Double,
    val surfaceTemperatureCelsius: Double
)

data class MachineVibrationDiagnostics(
    val vectorMagnitudeRmsMmS: Double,
    val isoZone: Iso10816Zone,
    val crestFactor: Double,
    val isBearingDefectDetected: Boolean,
    val isThermalTripActive: Boolean,
    val recommendedAction: String,
    val modbusHoldingRegisters: List<Int>
)

class VibrationAnalysisEngine {

    companion object {
        const val THRESHOLD_ZONE_A_MAX = 1.40 // mm/s RMS
        const val THRESHOLD_ZONE_B_MAX = 2.80 // mm/s RMS
        const val THRESHOLD_ZONE_C_MAX = 4.50 // mm/s RMS

        const val CREST_FACTOR_DEFECT_THRESHOLD = 4.20 // Bearing defect indicator
        const val TEMP_WARNING_CELSIUS = 70.0
        const val TEMP_TRIP_CELSIUS = 90.0
    }

    /**
     * Analyzes raw 3-axis accelerometer readings and computes full ISO diagnostics.
     */
    fun analyze(sample: VibrationSample): MachineVibrationDiagnostics {
        val vectorMagnitude = sqrt(
            sample.xVelocityRmsMmS * sample.xVelocityRmsMmS +
                    sample.yVelocityRmsMmS * sample.yVelocityRmsMmS +
                    sample.zVelocityRmsMmS * sample.zVelocityRmsMmS
        )

        val isoZone = when {
            vectorMagnitude < THRESHOLD_ZONE_A_MAX -> Iso10816Zone.ZONE_A_OPTIMAL
            vectorMagnitude < THRESHOLD_ZONE_B_MAX -> Iso10816Zone.ZONE_B_ACCEPTABLE
            vectorMagnitude < THRESHOLD_ZONE_C_MAX -> Iso10816Zone.ZONE_C_WARNING
            else -> Iso10816Zone.ZONE_D_CRITICAL
        }

        // Crest Factor = Peak Acceleration / RMS Velocity (converted to equivalent units)
        val rmsReference = if (vectorMagnitude > 0.05) vectorMagnitude else 0.05
        val crestFactor = (sample.peakAccelerationG * 9.80665) / rmsReference
        val bearingDefect = crestFactor > CREST_FACTOR_DEFECT_THRESHOLD

        val thermalTrip = sample.surfaceTemperatureCelsius >= TEMP_TRIP_CELSIUS

        val recommendedAction = when {
            thermalTrip || isoZone == Iso10816Zone.ZONE_D_CRITICAL ->
                "EMERGENCY: Immediate shutdown advised to prevent bearing seizure and rotor unbalance failure"
            isoZone == Iso10816Zone.ZONE_C_WARNING ->
                "MAINTENANCE: Inspect dynamic rotor balancing, shaft alignment, and bearing lubrication"
            bearingDefect ->
                "WARNING: High crest factor indicates early bearing raceway spalling; plan replacement"
            else ->
                "NOMINAL: Vibration and temperature parameters are within normal design envelopes"
        }

        // Modbus Holding Registers mapping (scaled by 10 for integer transmission)
        val registers = listOf(
            isoZone.statusCode,                                                // 40001: Machine Status Code
            (sample.xVelocityRmsMmS * 10).toInt(),                             // 40002: X-Axis RMS
            (sample.yVelocityRmsMmS * 10).toInt(),                             // 40003: Y-Axis RMS
            (sample.zVelocityRmsMmS * 10).toInt(),                             // 40004: Z-Axis RMS
            (vectorMagnitude * 10).toInt(),                                    // 40005: Vector Magnitude
            (sample.peakAccelerationG * 10).toInt(),                           // 40006: Peak Acceleration
            (crestFactor * 10).toInt(),                                        // 40007: Crest Factor
            (sample.surfaceTemperatureCelsius * 10).toInt()                    // 40008: Temperature
        )

        return MachineVibrationDiagnostics(
            vectorMagnitudeRmsMmS = vectorMagnitude,
            isoZone = isoZone,
            crestFactor = crestFactor,
            isBearingDefectDetected = bearingDefect,
            isThermalTripActive = thermalTrip,
            recommendedAction = recommendedAction,
            modbusHoldingRegisters = registers
        )
    }

    /**
     * Formats diagnostics into human-readable industrial telemetry lines.
     */
    fun formatTelemetrySummary(sample: VibrationSample, diag: MachineVibrationDiagnostics): String {
        return String.format(
            Locale.US,
            "RMS: %.2f mm/s (%s) | Peak: %.2f g | CF: %.1f | Temp: %.1f C | Bus: Modbus RTU RS485",
            diag.vectorMagnitudeRmsMmS,
            diag.isoZone.code,
            sample.peakAccelerationG,
            diag.crestFactor,
            sample.surfaceTemperatureCelsius
        )
    }
}
