package dev.sphc.srccon.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sphc.srccon.ui.AppThemeMode
import dev.sphc.srccon.usb.UsbDeviceFingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.srcconDataStore by preferencesDataStore(name = "srccon_settings")

data class AppSettings(
    val theme: AppThemeMode = AppThemeMode.LIGHT,
    val maximumExposureSeconds: Int = DEFAULT_MAXIMUM_EXPOSURE_SECONDS,
    val singleExposureSeconds: Int = DEFAULT_SINGLE_EXPOSURE_SECONDS,
    /** Display hint only. Reading this value must never select or connect USB. */
    val lastDeviceFingerprint: String? = null,
    /** Conservative crash-recovery marker; it does not prove relay contact position. */
    val mayBeEnergized: Boolean = false,
)

const val DEFAULT_MAXIMUM_EXPOSURE_SECONDS = 600
const val DEFAULT_SINGLE_EXPOSURE_SECONDS = 30
const val LARGEST_MAXIMUM_EXPOSURE_SECONDS = 86_400

/** Pure helpers shared by DataStore and JVM tests. */
object SettingsPolicy {
    fun requireMaximumExposure(seconds: Int) {
        require(seconds in 1..LARGEST_MAXIMUM_EXPOSURE_SECONDS) {
            "Maximum exposure must be between 1 and $LARGEST_MAXIMUM_EXPOSURE_SECONDS seconds"
        }
    }

    fun requireSingleExposure(seconds: Int, maximumSeconds: Int) {
        require(seconds in 1..maximumSeconds) {
            "Single exposure must be between 1 and the maximum exposure"
        }
    }

    fun normalizedDurations(rawMaximum: Int?, rawSingle: Int?): Pair<Int, Int> {
        val maximum = (rawMaximum ?: DEFAULT_MAXIMUM_EXPOSURE_SECONDS)
            .coerceIn(1, LARGEST_MAXIMUM_EXPOSURE_SECONDS)
        val single = (rawSingle ?: DEFAULT_SINGLE_EXPOSURE_SECONDS).coerceIn(1, maximum)
        return maximum to single
    }
}

/** Ordinary preferences. Risk-marker methods are exposed through a separate core adapter. */
class SrcconSettingsRepository(context: Context) {
    private val store = context.applicationContext.srcconDataStore

    // Do not turn a storage read failure into defaults: that could hide a persisted risk marker.
    val settings: Flow<AppSettings> = store.data.map(::toSettings)

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(theme: AppThemeMode) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setMaximumExposureSeconds(seconds: Int) {
        SettingsPolicy.requireMaximumExposure(seconds)
        store.edit { values ->
            values[MAX_EXPOSURE] = seconds
            val currentSingle = values[SINGLE_EXPOSURE] ?: DEFAULT_SINGLE_EXPOSURE_SECONDS
            values[SINGLE_EXPOSURE] = currentSingle.coerceIn(1, seconds)
        }
    }

    suspend fun setSingleExposureSeconds(seconds: Int) {
        store.edit { values ->
            val maximum = (values[MAX_EXPOSURE] ?: DEFAULT_MAXIMUM_EXPOSURE_SECONDS)
                .coerceIn(1, LARGEST_MAXIMUM_EXPOSURE_SECONDS)
            SettingsPolicy.requireSingleExposure(seconds, maximum)
            values[SINGLE_EXPOSURE] = seconds
        }
    }

    suspend fun setLastDeviceFingerprint(fingerprint: String?) {
        require(fingerprint == null || UsbDeviceFingerprint.decode(fingerprint) != null) {
            "Invalid USB device fingerprint hint"
        }
        store.edit { values ->
            if (fingerprint == null) values.remove(LAST_DEVICE_FINGERPRINT)
            else values[LAST_DEVICE_FINGERPRINT] = fingerprint
        }
    }

    suspend fun setMayBeEnergized(atRisk: Boolean) {
        store.edit { it[MAY_BE_ENERGIZED] = atRisk }
    }

    private fun toSettings(values: Preferences): AppSettings {
        val (maximum, single) = SettingsPolicy.normalizedDurations(
            values[MAX_EXPOSURE],
            values[SINGLE_EXPOSURE],
        )
        val theme = values[THEME]?.let { saved ->
            AppThemeMode.entries.firstOrNull { it.name == saved }
        } ?: AppThemeMode.LIGHT
        return AppSettings(
            theme = theme,
            maximumExposureSeconds = maximum,
            singleExposureSeconds = single,
            lastDeviceFingerprint = values[LAST_DEVICE_FINGERPRINT],
            mayBeEnergized = values[MAY_BE_ENERGIZED] ?: false,
        )
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val MAX_EXPOSURE = intPreferencesKey("maximum_exposure_seconds")
        val SINGLE_EXPOSURE = intPreferencesKey("single_exposure_seconds")
        val LAST_DEVICE_FINGERPRINT = stringPreferencesKey("last_device_fingerprint_hint")
        val MAY_BE_ENERGIZED = booleanPreferencesKey("relay_may_be_energized")
    }
}
