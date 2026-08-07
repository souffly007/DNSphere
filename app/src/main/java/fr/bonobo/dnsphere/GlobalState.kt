package fr.bonobo.dnsphere

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalState {

    private val _isVpnRunning = MutableStateFlow(false) // ✅ DOIT être false par défaut
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    private val _isVpnPaused = MutableStateFlow(false)
    val isVpnPaused: StateFlow<Boolean> = _isVpnPaused.asStateFlow()

    fun setVpnRunning(running: Boolean) {
        _isVpnRunning.value = running
    }

    fun setVpnPaused(paused: Boolean) {
        _isVpnPaused.value = paused
    }

    // ✅ Méthode atomique pour éviter les états intermédiaires
    fun updateState(running: Boolean, paused: Boolean) {
        _isVpnRunning.value = running
        _isVpnPaused.value = paused
    }

    // ✅ NOUVEAU : Reset complet (utile au démarrage de l'app)
    fun reset() {
        _isVpnRunning.value = false
        _isVpnPaused.value = false
    }
}