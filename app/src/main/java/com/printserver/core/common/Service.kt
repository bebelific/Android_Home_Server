package com.printserver.core.common

import kotlinx.coroutines.flow.StateFlow

interface Service {
    val id: String
    val displayName: String
    val defaultPort: Int
    suspend fun start(context: android.content.Context): Result<Unit>
    suspend fun stop(): Result<Unit>
    val state: StateFlow<ServiceState>
    fun isHealthy(): Boolean
}
