# محور لإنترنت الأشياء الصناعي - Mihwar Modbus Industrial IoT (`mihwar-modbus-industrial-iot-1289764`)
> منظومة مراقبة الاهتزازات وتحليل السلامة التنبؤية للآلات والتوربينات الصناعية عبر بروتوكول Modbus RTU / RS485

- **Project ID**: `#1289764`
- **Commercial Archetype**: `industrial_iot`
- **Bound Hardware Peripheral**: `3-Axis Industrial Vibration Accelerometer + Modbus RS485`
- **Package Name**: `sa.factory.mihwarmodbusindust`
- **Zero-Emoji Invariant**: Strictly Enforced (100% Vector Icons)

---

## [Sidekick Autonomous Update]

### Overview of Completed Fixes & Features
1. **Modbus RTU / RS485 Communication Protocol Engine (`ModbusRtuProtocolEngine.kt`)**:
   - Implemented standard 16-bit Modbus CRC calculation using polynomial 0xA001 with frame integrity verification.
   - Built request/response encoders for Function Code 0x03 (Read Holding Registers), 0x04 (Read Input Registers), 0x06, and 0x10.
   - Built robust exception frame handler covering standard Modbus exceptions (Illegal Function, Illegal Data Address, Illegal Data Value, and Slave Device Failure).

2. **3-Axis Vibration Analytics Engine (`VibrationAnalysisEngine.kt`)**:
   - Implemented international machine vibration evaluation standards (ISO 10816-3 / ISO 20816) classifying operating conditions into Zone A (Optimal < 1.4 mm/s), Zone B (Acceptable 1.4-2.8 mm/s), Zone C (Warning 2.8-4.5 mm/s), and Zone D (Critical > 4.5 mm/s).
   - Computes 3-axis vector resultant velocity ($v_{\text{total}} = \sqrt{v_x^2 + v_y^2 + v_z^2}$) and Bearing Crest Factor for early defect detection.
   - Embedded thermal protection threshold monitoring (Warning > 70 C, Emergency Trip > 90 C) and automated Modbus Holding Register mapping (Registers 40001-40008).

3. **Hardware Driver & MVVM Integration (`HardwareSensorDriver.kt` & `MihwarModbusViewModel.kt`)**:
   - Bound dual protocol engines into real-time telemetry Flow streaming live industrial telemetry frames, bus state, and machine diagnostic health into UI StateFlow.
   - Modernized UI dividers (`HorizontalDivider`), eliminated Compose Char literal syntax hazards, and corrected missing typography imports (`FontWeight`, `sp`).
   - Relocated and expanded unit regression test suite into `app/src/test/java` with 10 comprehensive test cases (`MihwarModbusRegressionTest.kt`).

4. **Resilience & Build Infrastructure**:
   - Added complete Gradle 8.10.2 wrapper with high-availability CDN distribution URL (`mirrors.cloud.tencent.com`).
   - Added high-availability Maven mirror (`maven.aliyun.com/repository/public`) to `settings.gradle.kts` to eliminate upstream 504 timeouts.

### Step-by-Step Instructions to Build and Test

```bash
# 1. Clean and build the Android Debug APK
./gradlew :app:assembleDebug

# 2. Run the 10-case regression unit test suite
./gradlew testDebugUnitTest

# 3. Verify test report
cat app/build/reports/tests/testDebugUnitTest/index.html
```
