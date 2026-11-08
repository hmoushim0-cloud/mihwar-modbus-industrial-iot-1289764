package sa.factory.mihwarmodbusindust

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import sa.factory.mihwarmodbusindust.data.repository.MihwarModbusRepositoryImpl
import sa.factory.mihwarmodbusindust.engine.*
import sa.factory.mihwarmodbusindust.hardware.HardwareSensorDriver
import sa.factory.mihwarmodbusindust.model.MihwarModbusItem

class MihwarModbusRegressionTest {

    @Test
    fun testCheckoutCalculationAndVat15() = runBlocking {
        val sensorDriver = HardwareSensorDriver()
        val repository = MihwarModbusRepositoryImpl(sensorDriver)

        val item1 = MihwarModbusItem("t1", "مستشعر اهتزاز صناعي 3-محاور", "نطاق قياس 50g", 120.0, "معتمد", "العتاد")
        val item2 = MihwarModbusItem("t2", "بوابة تحويل RS485 إلى Modbus TCP", "بروتوكول صناعي", 180.0, "معتمد", "العتاد")

        repository.addToCart(item1)
        repository.addToCart(item2)

        val order = repository.checkout("Mada Contactless EMV")

        assertEquals(300.0, order.subtotalSar, 0.01)
        assertEquals(45.0, order.vatSar, 0.01)
        assertEquals(345.0, order.totalSar, 0.01)
        assertEquals(0, repository.activeCart.value.size)
    }

    @Test
    fun testModbusCrc16Calculation() {
        val engine = ModbusRtuProtocolEngine()
        // Standard Modbus RTU test vector: Slave 0x01, FC 0x03, Start 0x0000, Count 0x0002 -> CRC is 0xC40B
        val testPacket = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x02)
        val crc = engine.calculateCrc16(testPacket)

        val expectedLow = 0xC4
        val expectedHigh = 0x0B

        assertEquals(expectedLow, crc and 0xFF)
        assertEquals(expectedHigh, (crc ushr 8) and 0xFF)
    }

    @Test
    fun testModbusCrcVerificationValidAndCorrupted() {
        val engine = ModbusRtuProtocolEngine()
        val validFrame = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x02, 0xC4.toByte(), 0x0B.toByte())
        assertTrue("Valid Modbus CRC frame must pass verification", engine.verifyCrc(validFrame))

        val corruptedFrame = validFrame.clone()
        corruptedFrame[2] = 0x05 // Tamper with payload byte
        assertFalse("Corrupted frame must fail CRC verification", engine.verifyCrc(corruptedFrame))
    }

    @Test
    fun testModbusReadHoldingRegistersRequestFrame() {
        val engine = ModbusRtuProtocolEngine()
        val request = engine.buildReadHoldingRegistersRequest(slaveAddress = 1, startRegister = 0, registerCount = 8)

        assertEquals("Modbus request must have 8 bytes", 8, request.size)
        assertEquals("Slave address must be 1", 1, request[0].toInt())
        assertEquals("Function code must be 0x03", 0x03, request[1].toInt())
        assertTrue("Built frame must have valid appended CRC", engine.verifyCrc(request))
    }

    @Test
    fun testModbusReadResponseDecoding() {
        val engine = ModbusRtuProtocolEngine()
        val registers = listOf(1, 14, 28, 45, 52)
        val responseFrame = engine.buildReadResponseFrame(slaveAddress = 1, functionCode = 0x03, registerValues = registers)

        val parsed = engine.parseResponseFrame(responseFrame)

        assertTrue(parsed.isValidCrc)
        assertFalse(parsed.isException)
        assertEquals(1, parsed.slaveAddress)
        assertEquals(0x03, parsed.functionCode)
        assertEquals(5, parsed.registers.size)
        assertEquals(registers, parsed.registers)
    }

    @Test
    fun testModbusExceptionFrameHandling() {
        val engine = ModbusRtuProtocolEngine()
        // Slave 1, Exception on FC 0x03 (0x83), Exception Code 0x02 (ILLEGAL_DATA_ADDRESS)
        val excFrameWithoutCrc = byteArrayOf(0x01, 0x83.toByte(), 0x02)
        val crc = engine.calculateCrc16(excFrameWithoutCrc)
        val fullExcFrame = byteArrayOf(
            0x01,
            0x83.toByte(),
            0x02,
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte()
        )

        val parsed = engine.parseResponseFrame(fullExcFrame)

        assertTrue(parsed.isValidCrc)
        assertTrue(parsed.isException)
        assertEquals(0x03, parsed.functionCode)
        assertEquals(0x02, parsed.exceptionCode)
        assertTrue(parsed.errorMessage.contains("ILLEGAL_DATA_ADDRESS"))
    }

    @Test
    fun testVibrationVectorMagnitudeCalculation() {
        val engine = VibrationAnalysisEngine()
        // 3-4-0 Pythagorean triangle with 0 Z: sqrt(3^2 + 4^2 + 0^2) = 5.0
        val sample = VibrationSample(3.0, 4.0, 0.0, 1.0, 40.0)
        val diag = engine.analyze(sample)

        assertEquals(5.0, diag.vectorMagnitudeRmsMmS, 0.001)
    }

    @Test
    fun testIso10816SeverityZones() {
        val engine = VibrationAnalysisEngine()

        // Zone A: < 1.4 mm/s
        val sampleA = VibrationSample(0.5, 0.5, 0.5, 0.2, 35.0)
        assertEquals(Iso10816Zone.ZONE_A_OPTIMAL, engine.analyze(sampleA).isoZone)

        // Zone B: 1.4 - 2.8 mm/s
        val sampleB = VibrationSample(1.2, 1.2, 0.5, 0.4, 45.0)
        assertEquals(Iso10816Zone.ZONE_B_ACCEPTABLE, engine.analyze(sampleB).isoZone)

        // Zone C: 2.8 - 4.5 mm/s
        val sampleC = VibrationSample(2.0, 2.0, 1.5, 0.8, 60.0)
        assertEquals(Iso10816Zone.ZONE_C_WARNING, engine.analyze(sampleC).isoZone)

        // Zone D: > 4.5 mm/s
        val sampleD = VibrationSample(3.0, 3.5, 2.5, 1.5, 75.0)
        assertEquals(Iso10816Zone.ZONE_D_CRITICAL, engine.analyze(sampleD).isoZone)
    }

    @Test
    fun testBearingCrestFactorAndThermalTrip() {
        val engine = VibrationAnalysisEngine()

        // High Peak Acceleration relative to RMS -> High Crest Factor (> 4.2)
        val bearingDefectSample = VibrationSample(0.4, 0.4, 0.2, 1.2, 50.0)
        val diagDefect = engine.analyze(bearingDefectSample)
        assertTrue("High peak ratio must detect bearing fault", diagDefect.isBearingDefectDetected)
        assertFalse("Normal temperature should not trip", diagDefect.isThermalTripActive)

        // Thermal trip (temp >= 90 C)
        val overheatSample = VibrationSample(0.5, 0.5, 0.5, 0.2, 92.5)
        val diagOverheat = engine.analyze(overheatSample)
        assertTrue("Surface temp >= 90 C must trigger thermal trip", diagOverheat.isThermalTripActive)
        assertTrue(diagOverheat.recommendedAction.contains("EMERGENCY"))
    }

    @Test
    fun testHardwareSensorDriverBinding() = runBlocking {
        val driver = HardwareSensorDriver()
        assertNotNull(driver.getModbusEngine())
        assertNotNull(driver.getVibrationEngine())

        val firstFrame = driver.observePeripheralStream().first()
        assertEquals("3-Axis Industrial Vibration Accelerometer + Modbus RS485", firstFrame.peripheralName)
        assertEquals("CONNECTING", firstFrame.connectionState)
    }
}
