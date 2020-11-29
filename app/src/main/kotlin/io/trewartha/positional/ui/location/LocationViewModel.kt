package io.trewartha.positional.ui.location

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Looper
import androidx.core.content.edit
import androidx.lifecycle.*
import com.google.android.gms.location.*
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import io.trewartha.positional.PositionalApplication
import io.trewartha.positional.R
import io.trewartha.positional.domain.entities.CoordinatesFormat
import io.trewartha.positional.domain.entities.Units
import io.trewartha.positional.location.LocationFormatter
import io.trewartha.positional.ui.ViewModelEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LocationViewModel(private val app: Application) : AndroidViewModel(app) {

    val bearing: LiveData<String> by lazy {
        location.mapNotNull { it }
                .map { locationFormatter.getBearing(it) ?: app.getString(R.string.common_dash) }
                .asLiveData()
    }

    val bearingAccuracy: LiveData<String> by lazy {
        location.mapNotNull { it }
                .map {
                    locationFormatter.getBearingAccuracy(it) ?: app.getString(R.string.common_dash)
                }
                .asLiveData()
    }

    val coordinates: LiveData<Coordinates> by lazy {
        _coordinates.mapNotNull { it }.asLiveData()
    }

    val coordinatesCopyEvents: LiveData<CoordinatesCopyEvent>
        get() = _coordinatesCopyEvents

    val coordinatesShareEvents: LiveData<CoordinatesShareEvent>
        get() = _coordinatesShareEvents

    val elevation: LiveData<String> by lazy {
        combine(location.mapNotNull { it }, units) { location, units ->
            locationFormatter.getElevation(location, units) ?: app.getString(R.string.common_dash)
        }.asLiveData()
    }

    val elevationAccuracy: LiveData<String> by lazy {
        combine(location.mapNotNull { it }, units) { location, units ->
            locationFormatter.getElevationAccuracy(location, units)
                    ?: app.getString(R.string.common_dash)
        }.asLiveData()
    }

    val screenLocked: LiveData<Boolean> by lazy {
        callbackFlow {
            if (prefs.contains(prefsKeyScreenLock))
                offer(prefs.getBoolean(prefsKeyScreenLock, DEFAULT_SCREEN_LOCK))
            prefScreenLockListener = PrefScreenLockListener(this)
            prefs.registerOnSharedPreferenceChangeListener(prefScreenLockListener)
            awaitClose {
                prefScreenLockListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
            }
        }.asLiveData()
    }

    val screenLockEvents: LiveData<ScreenLockEvent>
        get() = _screenLockEvents

    val speed: LiveData<String> by lazy {
        combine(location.mapNotNull { it }, units) { location, units ->
            locationFormatter.getSpeed(location, units) ?: app.getString(R.string.common_dash)
        }.asLiveData()
    }

    val speedAccuracy: LiveData<String> by lazy {
        combine(location.mapNotNull { it }, units) { location, units ->
            locationFormatter.getSpeedAccuracy(location, units)
                    ?: app.getString(R.string.common_dash)
        }.asLiveData()
    }

    val updatedAt: LiveData<String> by lazy {
        location.mapNotNull { it }
                .map { locationFormatter.getTimestamp(it) ?: app.getString(R.string.common_dash) }
                .asLiveData()
    }

    private val _coordinates: StateFlow<Coordinates?> by lazy {
        combine(
                location.mapNotNull { it },
                coordinatesFormat.mapNotNull { it }
        ) { location, format ->
            location.toCoordinates(format)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    }
    private val _coordinatesCopyEvents = MutableLiveData<CoordinatesCopyEvent>()
    private val _coordinatesShareEvents = MutableLiveData<CoordinatesShareEvent>()
    private val _screenLockEvents = MutableLiveData<ScreenLockEvent>()

    private val coordinatesFormat: StateFlow<CoordinatesFormat?> = callbackFlow {
        if (prefs.contains(prefsKeyCoordinatesFormat))
            offer(prefs.getString(prefsKeyCoordinatesFormat, null))
        prefCoordinatesFormatListener = PrefCoordinatesFormatListener(this)
        prefs.registerOnSharedPreferenceChangeListener(prefCoordinatesFormatListener)
        awaitClose {
            prefCoordinatesFormatListener?.let {
                prefs.unregisterOnSharedPreferenceChangeListener(it)
            }
        }
    }.map {
        CoordinatesFormat.valueOf(it!!.toUpperCase(Locale.US))
    }.catch {
        emit(DEFAULT_COORDINATES_FORMAT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private val location: StateFlow<Location?> = callbackFlow<Location> {
        var firstLocationUpdateTrace: Trace? =
                FirebasePerformance.getInstance().newTrace("first_location")
        val locationClient = LocationServices.getFusedLocationProviderClient(app)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                val location = locationResult?.lastLocation ?: return
                offer(location)

                if (firstLocationUpdateTrace != null) {
                    val accuracyCounter = when (location.accuracy) {
                        in 0.0f.rangeTo(5.0f) -> COUNTER_ACCURACY_VERY_HIGH
                        in 5.0f.rangeTo(10.0f) -> COUNTER_ACCURACY_HIGH
                        in 10.0f.rangeTo(15.0f) -> COUNTER_ACCURACY_MEDIUM
                        in 15.0f.rangeTo(20.0f) -> COUNTER_ACCURACY_LOW
                        else -> COUNTER_ACCURACY_VERY_LOW
                    }
                    firstLocationUpdateTrace?.incrementMetric(accuracyCounter, 1L)
                    firstLocationUpdateTrace?.stop()
                    firstLocationUpdateTrace = null
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                Timber.d("Location availability changed to $locationAvailability")
            }
        }

        try {
            val locationRequest = LocationRequest.create()
                    .setPriority(LOCATION_UPDATE_PRIORITY)
                    .setInterval(LOCATION_UPDATE_INTERVAL_MS)
            Timber.i("Requesting location updates: $locationRequest")
            if (firstLocationUpdateTrace == null) {
                firstLocationUpdateTrace?.start()
            }
            locationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Timber.w(e, "Don't have location permissions, no location updates will be received")
        }

        awaitClose {
            Timber.i("Suspending location updates")
            locationClient.removeLocationUpdates(locationCallback)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private val units: SharedFlow<Units> = callbackFlow {
        if (prefs.contains(prefsKeyUnits))
            offer(prefs.getString(prefsKeyUnits, null))
        prefUnitsListener = PrefUnitsListener(this)
        prefs.registerOnSharedPreferenceChangeListener(prefUnitsListener)
        awaitClose {
            prefUnitsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        }
    }.map {
        Units.valueOf(it!!.toUpperCase(Locale.US))
    }.catch {
        emit(DEFAULT_UNITS)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), 1)

    private val clipboardManager
            by lazy { app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private val locationFormatter = LocationFormatter(app)
    private val prefs = app.getSharedPreferences(
            app.getString(R.string.settings_filename),
            Context.MODE_PRIVATE
    )
    private val prefsKeyCoordinatesFormat = app.getString(R.string.settings_coordinates_format_key)
    private val prefsKeyScreenLock = app.getString(R.string.settings_screen_lock_key)
    private val prefsKeyUnits = app.getString(R.string.settings_units_key)
    private var prefCoordinatesFormatListener: PrefCoordinatesFormatListener? = null
    private var prefScreenLockListener: PrefScreenLockListener? = null
    private var prefUnitsListener: PrefUnitsListener? = null

    fun handleViewEvent(event: LocationFragment.Event) {
        when (event) {
            is LocationFragment.Event.CopyClick -> handleCopyClick()
            is LocationFragment.Event.ScreenLockClick -> handleScreenLockClick()
            is LocationFragment.Event.ShareClick -> handleShareClick()
        }
    }

    private fun handleCopyClick() {
        val location = location.value
        val format = coordinatesFormat.value
        _coordinatesCopyEvents.value = if (location == null || format == null) {
            CoordinatesCopyEvent.Error()
        } else {
            clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(
                            getApplication<PositionalApplication>()
                                    .getString(R.string.location_copied_coordinates_label),
                            locationFormatter.getSharedCoordinates(location, format)
                    )
            )
            CoordinatesCopyEvent.Success()
        }
    }

    private fun handleScreenLockClick() {
        val locked = !prefs.getBoolean(prefsKeyScreenLock, false)
        prefs.edit { putBoolean(prefsKeyScreenLock, locked) }
        _screenLockEvents.value = ScreenLockEvent(locked)
    }

    private fun handleShareClick() {
        val location = location.value
        val format = coordinatesFormat.value
        _coordinatesShareEvents.value = if (location == null || format == null)
            CoordinatesShareEvent.Error()
        else
            CoordinatesShareEvent.Success(locationFormatter.getSharedCoordinates(location, format))
    }

    private fun Location.toCoordinates(format: CoordinatesFormat): Coordinates {
        val (coordinates, coordinatesLines) = locationFormatter.getCoordinates(this, format)
        return Coordinates(coordinatesLines, coordinates)
    }

    data class Coordinates(val maxLines: Int, val text: String)

    sealed class CoordinatesCopyEvent : ViewModelEvent() {
        class Error : CoordinatesCopyEvent()
        class Success : CoordinatesCopyEvent()
    }

    sealed class CoordinatesShareEvent : ViewModelEvent() {
        class Error : CoordinatesShareEvent()
        data class Success(val coordinates: String) : CoordinatesShareEvent()
    }

    data class ScreenLockEvent(val locked: Boolean) : ViewModelEvent()

    private inner class PrefCoordinatesFormatListener(
            val producerScope: ProducerScope<String?>
    ) : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences, key: String) {
            if (key == prefsKeyCoordinatesFormat)
                producerScope.offer(sharedPrefs.getString(key, null))
        }
    }

    private inner class PrefScreenLockListener(
            val producerScope: ProducerScope<Boolean>
    ) : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences, key: String) {
            if (key == prefsKeyScreenLock)
                producerScope.offer(sharedPrefs.getBoolean(key, DEFAULT_SCREEN_LOCK))
        }
    }

    private inner class PrefUnitsListener(
            val producerScope: ProducerScope<String?>
    ) : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences, key: String) {
            if (key == prefsKeyUnits)
                producerScope.offer(sharedPrefs.getString(key, null))
        }
    }

    companion object {
        private val DEFAULT_COORDINATES_FORMAT = CoordinatesFormat.DD
        private const val DEFAULT_SCREEN_LOCK = false
        private val DEFAULT_UNITS = Units.METRIC
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val LOCATION_UPDATE_PRIORITY = LocationRequest.PRIORITY_HIGH_ACCURACY
        private const val COUNTER_ACCURACY_VERY_HIGH = "accuracy_very_high"
        private const val COUNTER_ACCURACY_HIGH = "accuracy_high"
        private const val COUNTER_ACCURACY_MEDIUM = "accuracy_medium"
        private const val COUNTER_ACCURACY_LOW = "accuracy_low"
        private const val COUNTER_ACCURACY_VERY_LOW = "accuracy_very_low"
    }
}