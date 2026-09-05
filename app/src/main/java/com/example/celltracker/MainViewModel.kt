package com.example.celltracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cellular = CellularRepository(app)
    private val location = LocationRepository(app)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var cellJob: Job? = null
    private var locationJob: Job? = null

    fun start() {
        if (cellJob?.isActive != true) {
            cellJob = viewModelScope.launch {
                while (true) {
                    try {
                        val (serving, neighbors) = cellular.readCells()
                        _state.value = _state.value.copy(
                            servingCell = serving,
                            neighborCount = neighbors,
                            lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                            error = null
                        )
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(error = e.message ?: "Unable to read cellular info")
                    }
                    delay(2000L)
                }
            }
        }

        if (locationJob?.isActive != true) {
            locationJob = viewModelScope.launch {
                location.locations().collect { loc ->
                    _state.value = _state.value.copy(location = loc)
                }
            }
        }
    }
}
