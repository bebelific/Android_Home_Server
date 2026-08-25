package com.printserver.core.common

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServiceRegistry(val appContext: Context) {
    private val services = LinkedHashMap<String, Service>()
    private val _servicesSnapshot = MutableStateFlow<List<Service>>(emptyList())
    val servicesSnapshot: StateFlow<List<Service>> = _servicesSnapshot

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun register(service: Service) {
        services[service.id] = service
        _servicesSnapshot.value = services.values.toList()
    }

    @Synchronized
    fun get(id: String): Service? = services[id]

    val allServices: List<Service> get() = synchronized(this) { services.values.toList() }

    fun stopAll() {
        scope.launch { allServices.forEach { runCatching { it.stop() } } }
    }
}
