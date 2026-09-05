package com.example.celltracker

data class CellData(
    val rat: String = "--",
    val operator: String = "--",
    val mcc: String = "--",
    val mnc: String = "--",
    val tac: String = "--",
    val cellId: String = "--",
    val pci: String = "--",
    val arfcn: String = "--",
    val rsrp: String = "--",
    val rsrq: String = "--",
    val sinr: String = "--",
    val registered: Boolean = false
)

data class LocationData(
    val latitude: String = "--",
    val longitude: String = "--",
    val altitude: String = "--",
    val accuracy: String = "--",
    val speedKmh: String = "--",
    val bearing: String = "--"
)

data class AppState(
    val servingCell: CellData = CellData(),
    val neighborCount: Int = 0,
    val location: LocationData = LocationData(),
    val error: String? = null,
    val lastUpdated: String = "--"
)
