package com.hermesandroid.bridge.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Requests all runtime permissions on first launch so the user doesn't have
 * to manually grant them later when voice/camera/etc. are needed.
 *
 * Call [requestAllPermissions] from your Activity's onCreate/onResume.
 * It handles the Android permission dialog and SYSTEM_ALERT_WINDOW redirect.
 */
object PermissionHelper {

    /** All runtime permissions the app might need. */
    private val ALL_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
    }

    private const val REQUEST_CODE_ALL = 9001
    private const val REQUEST_CODE_OVERLAY = 9002
    private const val PREFS_NAME = "hermes_permission_prefs"
    private const val KEY_PERMISSIONS_ASKED = "permissions_asked_before"

    /**
     * Check which permissions are missing and request them.
     * Call from Activity.onCreate().
     * Returns true if a permission dialog was shown.
     */
    fun requestAllPermissions(activity: Activity): Boolean {
        // Only show the full permission ask once
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)

        // Even if asked before, check for any still-missing critical permissions
        val missing = ALL_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty() && Settings.canDrawOverlays(activity)) {
            // Everything's already granted
            if (!alreadyAsked) {
                prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
            }
            return false
        }

        // Request runtime permissions
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE_ALL
            )
        }

        // SYSTEM_ALERT_WINDOW needs a settings redirect
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY)
        }

        prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
        return true
    }

    /**
     * Check if ALL critical permissions (mic, camera) are granted.
     */
    fun hasCriticalPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * List which permissions are still missing (for diagnostics).
     */
    fun getMissingPermissions(context: Context): List<String> {
        return ALL_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}
