package com.screensmithy.app.domain.repository

import com.screensmithy.app.domain.model.KioskConfiguration
import kotlinx.coroutines.flow.Flow

/**
 * Contract for managing persisted ScreenSmithy kiosk configuration.
 */
interface ConfigurationRepository {

    /**
     * Observable stream of configuration updates.
     */
    val configurationFlow: Flow<KioskConfiguration>

    /**
     * Reads the current configuration snapshot.
     */
    suspend fun getConfiguration(): KioskConfiguration

    /**
     * Atomically mutates configuration using the provided transformation.
     * Validates domain invariants before writing.
     *
     * @return [Result.success] with updated configuration or [Result.failure] on validation/I/O error.
     */
    suspend fun updateConfiguration(
        transform: (current: KioskConfiguration) -> KioskConfiguration
    ): Result<KioskConfiguration>

    /**
     * Resets configuration back to clean default factory state.
     */
    suspend fun resetConfiguration(): Result<KioskConfiguration>
}
