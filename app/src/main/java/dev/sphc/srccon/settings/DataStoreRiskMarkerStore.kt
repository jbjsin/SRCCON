package dev.sphc.srccon.settings

import dev.sphc.srccon.core.RelayConfigurationSnapshot
import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.RiskMarkerStore
import kotlinx.coroutines.CancellationException

/** Separate safety adapter so a storage read failure can never be interpreted as safe/OFF. */
class DataStoreRiskMarkerStore(
    private val settings: SrcconSettingsRepository,
) : RiskMarkerStore {
    override suspend fun markAtRisk(snapshot: RelayConfigurationSnapshot) {
        // Demo Mode cannot energize physical hardware and must not create a
        // misleading crash-recovery requirement on the next app launch.
        if (snapshot.target is RelayTarget.Usb) settings.setMayBeEnergized(true)
    }

    override suspend fun clear() {
        settings.setMayBeEnergized(false)
    }

    override suspend fun isAtRisk(): Boolean = try {
        settings.current().mayBeEnergized
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }
}
