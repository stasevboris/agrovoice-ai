package com.agronomy.phytomonitor.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agronomy.phytomonitor.journal.GeoPoint

/** One reading of the receiver: WGS84 degrees, accuracy radius in metres, time of the fix. */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long, val provider: String) {
    fun point() = GeoPoint(lat, lon, accuracyM)
}

/** Which fix to keep and which one is fresh enough to describe where a photo was taken. Pure Kotlin. */
object LocationFixes {
    /** A photo is tied to a fix not older than this. */
    const val MAX_AGE_MS = 2 * 60 * 1000L

    /** A newer reading wins after this gap even if it is less accurate. */
    const val NEWER_WINS_MS = 30 * 1000L

    fun better(current: LocationFix?, candidate: LocationFix): LocationFix {
        if (current == null) {
            return candidate
        }
        val newer = candidate.timeMs - current.timeMs
        return when {
            newer > NEWER_WINS_MS -> candidate
            newer < -NEWER_WINS_MS -> current
            candidate.accuracyM <= current.accuracyM -> candidate
            else -> current
        }
    }

    fun forInspection(fix: LocationFix?, nowMs: Long): GeoPoint? {
        if (fix == null || nowMs - fix.timeMs > MAX_AGE_MS || fix.timeMs - nowMs > NEWER_WINS_MS) {
            return null
        }
        return fix.point()
    }

    /** «±8 м, 12 с назад» — what the agronomist sees next to the plot field. */
    fun describe(fix: LocationFix?, nowMs: Long): String {
        if (fix == null) {
            return "Ищу спутники…"
        }
        val seconds = ((nowMs - fix.timeMs) / 1000).coerceAtLeast(0)
        val age = if (seconds < 60) "$seconds с назад" else "${seconds / 60} мин назад"
        val stale = if (forInspection(fix, nowMs) == null) " — устарело, к осмотру не прикрепится" else ""
        return "±${fix.accuracyM.toInt()} м, $age$stale"
    }
}

/**
 * Android LocationManager without Google Play services: GPS works offline; the network provider is
 * used only when the phone has it enabled. Updates run only while the screen is in the foreground.
 */
class FieldLocation(context: Context) : LocationListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    var latest by mutableStateOf<LocationFix?>(null); private set
    var active by mutableStateOf(false); private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    /** False when there is no permission or no enabled provider. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active) {
            return true
        }
        if (manager == null || !hasPermission()) {
            return false
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            return false
        }
        providers.forEach { provider ->
            manager.getLastKnownLocation(provider)?.let(::onLocationChanged)
            manager.requestLocationUpdates(provider, UPDATE_MS, 0f, this, Looper.getMainLooper())
        }
        active = true
        return true
    }

    fun stop() {
        manager?.removeUpdates(this)
        active = false
    }

    override fun onLocationChanged(location: Location) {
        val fix = LocationFix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time, location.provider ?: "")
        latest = LocationFixes.better(latest, fix)
    }

    @Deprecated("Required by older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    private companion object {
        const val UPDATE_MS = 5000L
    }
}
