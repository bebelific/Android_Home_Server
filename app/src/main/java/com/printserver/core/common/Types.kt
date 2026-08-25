package com.printserver.core.common

enum class ServiceState { DISABLED, STARTING, RUNNING, STOPPING, ERROR }

enum class JobState { WAITING, RECEIVING, PRINTING, COMPLETED, FAILED, CANCELLED }

val JobState.isTerminal: Boolean
    get() = this == JobState.COMPLETED || this == JobState.FAILED || this == JobState.CANCELLED

data class ConnectionMeta(val remoteAddress: String, val receivedAtMillis: Long)
