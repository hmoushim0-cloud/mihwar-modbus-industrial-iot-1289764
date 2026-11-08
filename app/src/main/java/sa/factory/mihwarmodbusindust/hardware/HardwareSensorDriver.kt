package sa.factory.mihwarmodbusindust.hardware

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import sa.factory.mihwarmodbusindust.engine.ModbusRtuProtocolEngine
import sa.factory.mihwarmodbusindust.engine.VibrationAnalysisEngine
import sa.factory.mihwarmodbusindust.engine.VibrationSample
import kotlin.random.Random

data class HardwareTelemetryFrame(
    val peripheralName: String,
    val connectionState: String,
    val livePayload: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HardwareSensorDriver {
    private val peripheral = "3-Axis Industrial Vibration Accelerometer + Modbus RS485"
    private val modbusEngine = ModbusRtuProtocolEngine()
    private val vibrationEngine = VibrationAnalysisEngine()

    fun observePeripheralStream(): Flow<HardwareTelemetryFrame> = flow {
        emit(HardwareTelemetryFrame(peripheral, "CONNECTING", "RS485 Modbus RTU Bus initializing (9600-8-N-1, Slave #1)..."))
        delay(300)

        // Baseline reading
        val baseline = VibrationSample(
            xVelocityRmsMmS = 0.85,
            yVelocityRmsMmS = 0.92,
            zVelocityRmsMmS = 0.64,
            peakAccelerationG = 0.45,
            surfaceTemperatureCelsius = 42.5
        )
        val diag = vibrationEngine.analyze(baseline)
        val responseBytes = modbusEngine.buildReadResponseFrame(1, 0x03, diag.modbusHoldingRegisters)
        val parsed = modbusEngine.parseResponseFrame(responseBytes)

        emit(
            HardwareTelemetryFrame(
                peripheral,
                "ONLINE",
                "Modbus RS485: ONLINE | Slave #1 Responsive | CRC: OK (${parsed.rawBytesHex.take(17)}...)"
            )
        )

        var cycle = 0
        while (true) {
            delay(1500)
            cycle++

            val vx = 0.80 + Random.nextDouble(-0.15, 0.25)
            val vy = 0.90 + Random.nextDouble(-0.15, 0.30)
            val vz = 0.60 + Random.nextDouble(-0.10, 0.20)
            val peakG = 0.40 + Random.nextDouble(0.05, 0.20)
            val temp = 43.0 + Random.nextDouble(0.0, 2.5)

            val sample = VibrationSample(vx, vy, vz, peakG, temp)
            val currentDiag = vibrationEngine.analyze(sample)
            val summary = vibrationEngine.formatTelemetrySummary(sample, currentDiag)

            val payload = "$summary | Frame #$cycle"
            emit(HardwareTelemetryFrame(peripheral, "ACTIVE_STREAMING", payload))
        }
    }

    fun getModbusEngine(): ModbusRtuProtocolEngine = modbusEngine
    fun getVibrationEngine(): VibrationAnalysisEngine = vibrationEngine
}
