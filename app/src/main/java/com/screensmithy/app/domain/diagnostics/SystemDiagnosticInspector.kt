package com.screensmithy.app.domain.diagnostics

/**
 * Interface for querying system and kiosk diagnostic snapshots.
 */
interface SystemDiagnosticInspector {
    suspend fun inspect(): SystemDiagnosticSnapshot
}
