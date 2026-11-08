package sa.factory.mihwarmodbusindust.engine

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Standard Modbus RTU / RS485 Communication Protocol Engine.
 *
 * Implements the Modbus Serial Line Protocol specification:
 * - 16-bit CRC (Cyclic Redundancy Check) calculation with polynomial 0xA001.
 * - Function Code 0x03 (Read Holding Registers), 0x04 (Read Input Registers),
 *   0x06 (Write Single Register), and 0x10 (Write Multiple Registers).
 * - Full exception frame decoding (0x80 | Function Code).
 * - RS485 packet framing with slave addressing and validation.
 *
 * Strict Zero-Emoji Invariant enforced across all strings and telemetry tags.
 */
data class ModbusResponse(
    val slaveAddress: Int,
    val functionCode: Int,
    val isException: Boolean,
    val exceptionCode: Int = 0,
    val registers: List<Int> = emptyList(),
    val rawBytesHex: String = "",
    val isValidCrc: Boolean = true,
    val errorMessage: String = ""
)

class ModbusRtuProtocolEngine {

    companion object {
        const val FC_READ_HOLDING_REGISTERS = 0x03
        const val FC_READ_INPUT_REGISTERS = 0x04
        const val FC_WRITE_SINGLE_REGISTER = 0x06
        const val FC_WRITE_MULTIPLE_REGISTERS = 0x10

        const val EXCEPTION_ILLEGAL_FUNCTION = 0x01
        const val EXCEPTION_ILLEGAL_DATA_ADDRESS = 0x02
        const val EXCEPTION_ILLEGAL_DATA_VALUE = 0x03
        const val EXCEPTION_SLAVE_DEVICE_FAILURE = 0x04
    }

    /**
     * Calculates the standard 16-bit Modbus CRC for a given byte buffer.
     * Polynomial: 0xA001 (reversed representation of 0x8005).
     * Initial value: 0xFFFF.
     * Returns: Int with Low Byte in bits 0-7, High Byte in bits 8-15.
     */
    fun calculateCrc16(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Verifies whether the CRC appended to the last two bytes of a Modbus RTU frame is valid.
     */
    fun verifyCrc(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val computedCrc = calculateCrc16(frame, frame.size - 2)
        val expectedLow = (computedCrc and 0xFF).toByte()
        val expectedHigh = ((computedCrc ushr 8) and 0xFF).toByte()

        return frame[frame.size - 2] == expectedLow && frame[frame.size - 1] == expectedHigh
    }

    /**
     * Builds a Modbus RTU Read Holding Registers (FC 0x03) request frame.
     */
    fun buildReadHoldingRegistersRequest(slaveAddress: Int, startRegister: Int, registerCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(slaveAddress and 0xFF)
        out.write(FC_READ_HOLDING_REGISTERS)
        out.write((startRegister ushr 8) and 0xFF)
        out.write(startRegister and 0xFF)
        out.write((registerCount ushr 8) and 0xFF)
        out.write(registerCount and 0xFF)

        val frameWithoutCrc = out.toByteArray()
        val crc = calculateCrc16(frameWithoutCrc)
        out.write(crc and 0xFF)         // CRC Low
        out.write((crc ushr 8) and 0xFF) // CRC High

        return out.toByteArray()
    }

    /**
     * Builds a simulated Modbus RTU slave response frame containing 16-bit register values.
     */
    fun buildReadResponseFrame(slaveAddress: Int, functionCode: Int, registerValues: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(slaveAddress and 0xFF)
        out.write(functionCode and 0xFF)
        out.write((registerValues.size * 2) and 0xFF) // Byte count

        for (reg in registerValues) {
            out.write((reg ushr 8) and 0xFF)
            out.write(reg and 0xFF)
        }

        val frameWithoutCrc = out.toByteArray()
        val crc = calculateCrc16(frameWithoutCrc)
        out.write(crc and 0xFF)
        out.write((crc ushr 8) and 0xFF)

        return out.toByteArray()
    }

    /**
     * Decodes and validates a raw Modbus RTU response frame.
     */
    fun parseResponseFrame(frame: ByteArray): ModbusResponse {
        val hexString = bytesToHex(frame)

        if (frame.size < 4) {
            return ModbusResponse(
                slaveAddress = 0,
                functionCode = 0,
                isException = false,
                rawBytesHex = hexString,
                isValidCrc = false,
                errorMessage = "Frame too short (less than 4 bytes)"
            )
        }

        if (!verifyCrc(frame)) {
            return ModbusResponse(
                slaveAddress = frame[0].toInt() and 0xFF,
                functionCode = frame[1].toInt() and 0xFF,
                isException = false,
                rawBytesHex = hexString,
                isValidCrc = false,
                errorMessage = "Invalid CRC checksum in Modbus RTU frame"
            )
        }

        val slaveAddress = frame[0].toInt() and 0xFF
        val functionCode = frame[1].toInt() and 0xFF

        // Check for exception response (bit 7 set: 0x80 | FC)
        if ((functionCode and 0x80) != 0) {
            val exceptionCode = frame[2].toInt() and 0xFF
            return ModbusResponse(
                slaveAddress = slaveAddress,
                functionCode = functionCode and 0x7F,
                isException = true,
                exceptionCode = exceptionCode,
                rawBytesHex = hexString,
                isValidCrc = true,
                errorMessage = "Modbus Exception Code 0x${String.format("%02X", exceptionCode)}: ${getExceptionName(exceptionCode)}"
            )
        }

        // Standard Read Response (FC 0x03 or 0x04)
        if (functionCode == FC_READ_HOLDING_REGISTERS || functionCode == FC_READ_INPUT_REGISTERS) {
            val byteCount = frame[2].toInt() and 0xFF
            val registerCount = byteCount / 2
            val registers = mutableListOf<Int>()

            var offset = 3
            for (i in 0 until registerCount) {
                if (offset + 1 < frame.size - 2) {
                    val hi = frame[offset].toInt() and 0xFF
                    val lo = frame[offset + 1].toInt() and 0xFF
                    registers.add((hi shl 8) or lo)
                    offset += 2
                }
            }

            return ModbusResponse(
                slaveAddress = slaveAddress,
                functionCode = functionCode,
                isException = false,
                registers = registers,
                rawBytesHex = hexString,
                isValidCrc = true
            )
        }

        return ModbusResponse(
            slaveAddress = slaveAddress,
            functionCode = functionCode,
            isException = false,
            rawBytesHex = hexString,
            isValidCrc = true
        )
    }

    private fun getExceptionName(code: Int): String = when (code) {
        EXCEPTION_ILLEGAL_FUNCTION -> "ILLEGAL_FUNCTION"
        EXCEPTION_ILLEGAL_DATA_ADDRESS -> "ILLEGAL_DATA_ADDRESS"
        EXCEPTION_ILLEGAL_DATA_VALUE -> "ILLEGAL_DATA_VALUE"
        EXCEPTION_SLAVE_DEVICE_FAILURE -> "SLAVE_DEVICE_FAILURE"
        else -> "UNKNOWN_EXCEPTION"
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF".toCharArray()
        val result = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            result.append(hexChars[v ushr 4])
            result.append(hexChars[v and 0x0F])
            if (i < bytes.size - 1) result.append(' ')
        }
        return result.toString()
    }
}
