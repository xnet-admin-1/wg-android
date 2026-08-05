package com.zaneschepke.wireguardautotunnel.core.tether

import timber.log.Timber

/**
 * AdbForwarder now serves as a compatibility layer for the old API
 * but delegates to the new PersistentAdbConnection for actual functionality.
 */
object AdbForwarder {
    
    private const val TAG = "AdbForwarder"
    private var persistentConnection: PersistentAdbConnection? = null
    
    /**
     * Start ADB forwarding using the persistent connection manager
     */
    fun start(): Boolean {
        Timber.i("$TAG: Starting ADB forwarding")
        
        // Stop any existing connection first
        stop()
        
        // The actual connection will be started by AdbForwardingHandler
        // which has access to the application scope and dispatchers
        return true
    }

    /**
     * Stop ADB forwarding
     */
    fun stop() {
        persistentConnection?.stop()
        persistentConnection = null
        Timber.i("$TAG: Stopped ADB forwarding")
    }
    
    /**
     * Check if ADB forwarding is running
     */
    val isRunning: Boolean get() = persistentConnection != null
    
    /**
     * Set the persistent connection instance
     */
    fun setConnection(connection: PersistentAdbConnection) {
        persistentConnection = connection
    }
}