package com.solarbt.devices

import android.util.Log
import com.solarbt.RegisterInfo

object SmartBattery : RenogyDevice {

    private const val MAX_CELL_COUNT = 16
    private const val CURRENT_AVERAGE_WINDOW = 10
    private const val CURRENT_AVERAGE_RESET_THRESHOLD = 3f
    private const val CURRENT_EPSILON = 0.01f
    private const val CURRENT_AVERAGE_TRIM_COUNT = 7
    private val recentCurrents = ArrayDeque<Float>()

    // The register to read to get the device model/info.
    override val deviceInfoRegister = RegisterInfo(5122, 8, "Smart Battery Device Info")

    // A list of other registers/sections to read for detailed data.
    private val batteryInfoRegister = RegisterInfo(5042, 6, "Smart Battery Main Info")
    private val cellVoltageInfoRegister = RegisterInfo(5000, 17, "Cell Voltage Info")
    private val cellTemperatureInfoRegister = RegisterInfo(5017, 17, "Cell Temperature Info")

    override val dataRegisters = listOf(
        batteryInfoRegister,
        cellVoltageInfoRegister,
        cellTemperatureInfoRegister
    )

    override fun getInitialData(): List<RenogyData> {
        val initialData = mutableListOf(
            RenogyData("Model", "N/A"),
            RenogyData("Voltage", 0.0f, "V"),
            RenogyData("Current", 0.0f, "A"),
            RenogyData("State of Charge", 0.0f, "%"),
            RenogyData("Remaining Capacity", 0.0, "Ah"),
            RenogyData("Full Capacity", 0.0, "Ah"),
            RenogyData("Time Remaining", 0.0f, "h")
        )

        for (i in 1..MAX_CELL_COUNT) {
            initialData.add(RenogyData("Cell $i Voltage", 0.0f, "V", isVisible = false))
            initialData.add(RenogyData("Cell $i Temp", 0.0f, "°C", isVisible = false))
        }

        return initialData
    }

    override fun parseData(
        register: RegisterInfo,
        data: ByteArray,
        currentData: List<RenogyData>
    ): Boolean {
        val success = when (register.address) {
            deviceInfoRegister.address -> parseDeviceInfo(data, currentData)
            batteryInfoRegister.address -> parseBatteryInfo(data, currentData)
            cellVoltageInfoRegister.address -> parseCellVoltageInfo(data, currentData)
            cellTemperatureInfoRegister.address -> parseCellTemperatureInfo(data, currentData)
            else -> false
        }
        if (success) {
            Log.d(
                "SmartBattery",
                "parseData for address ${register.address} successful"
            )
        }
        return success
    }

    private fun parseDeviceInfo(data: ByteArray, currentData: List<RenogyData>): Boolean {
        val model = data.decodeToString(0, 16)
        currentData.find { it.key == "Model" }?.value = model
        return true
    }

    private fun parseBatteryInfo(data: ByteArray, currentData: List<RenogyData>): Boolean {
        val current = data.toInt16(0) * 0.01f
        val voltage = data.toUInt16(2) * 0.1f
        val remainingCapacity = data.toUInt32(4) * 0.001
        val fullCapacity = data.toUInt32(8) * 0.001
        val stateOfCharge =
            if (fullCapacity > 0) (remainingCapacity / fullCapacity * 100).toFloat() else 0.0f
        val averageCurrent = updateAverageCurrent(current)
        val timeRemaining = calculateTimeRemainingHours(
            averageCurrent = averageCurrent,
            remainingCapacity = remainingCapacity,
            fullCapacity = fullCapacity
        )

        currentData.find { it.key == "Voltage" }?.value = "%.1f".format(voltage)
        currentData.find { it.key == "Current" }?.value = "%.2f".format(current)
        currentData.find { it.key == "State of Charge" }?.value = stateOfCharge
        currentData.find { it.key == "Remaining Capacity" }?.value =
            "%.3f".format(remainingCapacity)
        currentData.find { it.key == "Full Capacity" }?.value = "%.3f".format(fullCapacity)
        currentData.find { it.key == "Time Remaining" }?.value =
            timeRemaining?.let { "%.2f".format(it) } ?: "N/A"
        return true
    }

    private fun updateAverageCurrent(current: Float): Float {
        val minCurrent = recentCurrents.minOrNull() ?: current
        val maxCurrent = recentCurrents.maxOrNull() ?: current

        if ((maxCurrent - minCurrent) >= CURRENT_AVERAGE_RESET_THRESHOLD) {
            repeat(minOf(CURRENT_AVERAGE_TRIM_COUNT, recentCurrents.size)) {
                recentCurrents.removeFirst()
            }
        }

        recentCurrents.addLast(current)
        while (recentCurrents.size > CURRENT_AVERAGE_WINDOW) {
            recentCurrents.removeFirst()
        }

        return recentCurrents.average().toFloat()
    }

    private fun calculateTimeRemainingHours(
        averageCurrent: Float,
        remainingCapacity: Double,
        fullCapacity: Double
    ): Float? {
        if (kotlin.math.abs(averageCurrent) < CURRENT_EPSILON) {
            return null
        }

        val hoursRemaining = if (averageCurrent > 0) {
            val capacityNeededToCharge = (fullCapacity - remainingCapacity).coerceAtLeast(0.0)
            capacityNeededToCharge / averageCurrent
        } else {
            val capacityNeededToDeplete = remainingCapacity.coerceAtLeast(0.0)
            -(capacityNeededToDeplete / kotlin.math.abs(averageCurrent))
        }

        return hoursRemaining
            .takeIf { it.isFinite() && it >= -9999 && it <= 9999 }
            ?.toFloat()
    }

    private fun parseCellVoltageInfo(data: ByteArray, currentData: List<RenogyData>): Boolean {
        val cellCount = data.toUInt16(0)
        Log.d("SmartBattery", "parseCellVoltageInfo: cellCount=$cellCount")

        for (i in 1..MAX_CELL_COUNT) {
            val cellData = currentData.find { it.key == "Cell $i Voltage" }
            if (cellData != null) {
                if (i <= cellCount) {
                    val voltage = data.toUInt16(2 + (i - 1) * 2) / 10.0f
                    cellData.value = "%.3f".format(voltage)
                    cellData.isVisible = true
                    Log.d("SmartBattery", "Cell $i Voltage: $voltage V")
                } else {
                    cellData.isVisible = false
                }
            }
        }
        return true
    }

    private fun parseCellTemperatureInfo(data: ByteArray, currentData: List<RenogyData>): Boolean {
        val cellCount = data.toUInt16(0)
        Log.d("SmartBattery", "parseCellTemperatureInfo: cellCount=$cellCount")

        for (i in 1..MAX_CELL_COUNT) {
            val cellData = currentData.find { it.key == "Cell $i Temp" }
            if (cellData != null) {
                if (i <= cellCount) {
                    val temperature = data.toInt16(2 + (i - 1) * 2) / 10.0f
                    cellData.value = "$temperature"
                    cellData.isVisible = true
                    Log.d("SmartBattery", "Cell $i Temp: $temperature °C")
                } else {
                    cellData.isVisible = false
                }
            }
        }
        return true
    }
}
