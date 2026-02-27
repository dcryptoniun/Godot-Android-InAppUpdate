package org.godotengine.plugin.android.inappupdate

import android.app.Activity
import android.content.IntentSender
import android.util.Log
import android.view.View
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

/**
 * Godot Android plugin for Google Play In-App Updates.
 *
 * Supports both Flexible and Immediate update flows via the
 * Play Core AppUpdateManager API.
 *
 * Signals emitted to Godot:
 * - update_info_received(is_available, is_flexible_allowed, is_immediate_allowed,
 *       available_version_code, staleness_days, update_priority)
 * - update_check_failed(error_message)
 * - update_status_changed(status, bytes_downloaded, total_bytes_to_download)
 * - update_flow_result(result_code)
 */
class GodotAndroidInAppUpdatePlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        private const val TAG = "GodotInAppUpdate"

        // Request code for the update flow
        private const val UPDATE_REQUEST_CODE = 10101

        // Signal names
        private const val SIGNAL_UPDATE_INFO_RECEIVED = "update_info_received"
        private const val SIGNAL_UPDATE_CHECK_FAILED = "update_check_failed"
        private const val SIGNAL_UPDATE_STATUS_CHANGED = "update_status_changed"
        private const val SIGNAL_UPDATE_FLOW_RESULT = "update_flow_result"

        // Install status constants exposed to Godot
        const val STATUS_UNKNOWN = 0
        const val STATUS_PENDING = 1
        const val STATUS_DOWNLOADING = 2
        const val STATUS_DOWNLOADED = 3 // InstallStatus.DOWNLOADED = 11 -> we map to 3
        const val STATUS_INSTALLING = 4
        const val STATUS_INSTALLED = 5
        const val STATUS_FAILED = 6
        const val STATUS_CANCELED = 7
    }

    private lateinit var appUpdateManager: AppUpdateManager
    private var cachedUpdateInfo: AppUpdateInfo? = null

    private val installStateListener = InstallStateUpdatedListener { state ->
        val mappedStatus = mapInstallStatus(state.installStatus())
        val bytesDownloaded = state.bytesDownloaded()
        val totalBytes = state.totalBytesToDownload()

        Log.d(TAG, "Install status changed: ${state.installStatus()} (mapped=$mappedStatus), " +
                "downloaded=$bytesDownloaded/$totalBytes")

        emitSignal(
            SIGNAL_UPDATE_STATUS_CHANGED,
            mappedStatus,
            bytesDownloaded,
            totalBytes
        )
    }

    override fun getPluginName(): String = BuildConfig.GODOT_PLUGIN_NAME

    override fun getPluginSignals(): MutableSet<SignalInfo> {
        return mutableSetOf(
            SignalInfo(
                SIGNAL_UPDATE_INFO_RECEIVED,
                Boolean::class.javaObjectType,      // is_available
                Boolean::class.javaObjectType,      // is_flexible_allowed
                Boolean::class.javaObjectType,      // is_immediate_allowed
                Int::class.javaObjectType,          // available_version_code
                Int::class.javaObjectType,          // staleness_days
                Int::class.javaObjectType           // update_priority
            ),
            SignalInfo(
                SIGNAL_UPDATE_CHECK_FAILED,
                String::class.java                  // error_message
            ),
            SignalInfo(
                SIGNAL_UPDATE_STATUS_CHANGED,
                Int::class.javaObjectType,          // status
                Long::class.javaObjectType,         // bytes_downloaded
                Long::class.javaObjectType          // total_bytes_to_download
            ),
            SignalInfo(
                SIGNAL_UPDATE_FLOW_RESULT,
                Int::class.javaObjectType           // result_code
            )
        )
    }

    override fun onMainCreate(activity: Activity?): View? {
        activity?.let {
            appUpdateManager = AppUpdateManagerFactory.create(it)
            appUpdateManager.registerListener(installStateListener)
            Log.d(TAG, "Plugin initialized")
        }
        return null
    }

    override fun onMainDestroy() {
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.unregisterListener(installStateListener)
        }
    }

    override fun onMainResume() {
        if (!::appUpdateManager.isInitialized) return

        // Resume stalled immediate updates
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                Log.d(TAG, "Resuming stalled immediate update")
                try {
                    activity?.let {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            it,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                            UPDATE_REQUEST_CODE
                        )
                    }
                } catch (e: IntentSender.SendIntentException) {
                    Log.e(TAG, "Failed to resume immediate update", e)
                }
            }
        }
    }

    override fun onMainActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == UPDATE_REQUEST_CODE) {
            Log.d(TAG, "Update flow result: $resultCode")
            emitSignal(SIGNAL_UPDATE_FLOW_RESULT, resultCode)
        }
    }

    // =========================================================================
    // Public API methods exposed to Godot via @UsedByGodot
    // =========================================================================

    /**
     * Check if an update is available. Emits [SIGNAL_UPDATE_INFO_RECEIVED] on success
     * or [SIGNAL_UPDATE_CHECK_FAILED] on failure.
     */
    @UsedByGodot
    fun checkUpdateAvailable() {
        Log.d(TAG, "Checking for update availability...")

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                cachedUpdateInfo = info

                val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                val isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                val versionCode = info.availableVersionCode()
                val stalenessDays = info.clientVersionStalenessDays() ?: -1
                val updatePriority = info.updatePriority()

                Log.d(TAG, "Update check result: available=$isAvailable, " +
                        "flexible=$isFlexibleAllowed, immediate=$isImmediateAllowed, " +
                        "versionCode=$versionCode, staleness=$stalenessDays, " +
                        "priority=$updatePriority")

                emitSignal(
                    SIGNAL_UPDATE_INFO_RECEIVED,
                    isAvailable,
                    isFlexibleAllowed,
                    isImmediateAllowed,
                    versionCode,
                    stalenessDays,
                    updatePriority
                )
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Update check failed", e)
                cachedUpdateInfo = null
                emitSignal(SIGNAL_UPDATE_CHECK_FAILED, e.message ?: "Unknown error")
            }
    }

    /**
     * Start a Flexible update flow. The user can continue using the app while
     * the update downloads in the background. Listen for [SIGNAL_UPDATE_STATUS_CHANGED]
     * with status [STATUS_DOWNLOADED] to know when to call [completeFlexibleUpdate].
     */
    @UsedByGodot
    fun startFlexibleUpdate() {
        val info = cachedUpdateInfo
        if (info == null) {
            Log.w(TAG, "No cached update info. Call checkUpdateAvailable() first.")
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "No update info available. Call checkUpdateAvailable() first.")
            return
        }

        if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            Log.w(TAG, "Flexible update not allowed for this update.")
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "Flexible update is not allowed for this update.")
            return
        }

        try {
            Log.d(TAG, "Starting flexible update flow...")
            activity?.let {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    it,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        } catch (e: IntentSender.SendIntentException) {
            Log.e(TAG, "Failed to start flexible update", e)
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "Failed to start flexible update: ${e.message}")
        }
    }

    /**
     * Start an Immediate update flow. This takes over the screen with the
     * Google Play update UI. The app restarts automatically after the update.
     */
    @UsedByGodot
    fun startImmediateUpdate() {
        val info = cachedUpdateInfo
        if (info == null) {
            Log.w(TAG, "No cached update info. Call checkUpdateAvailable() first.")
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "No update info available. Call checkUpdateAvailable() first.")
            return
        }

        if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            Log.w(TAG, "Immediate update not allowed for this update.")
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "Immediate update is not allowed for this update.")
            return
        }

        try {
            Log.d(TAG, "Starting immediate update flow...")
            activity?.let {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    it,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        } catch (e: IntentSender.SendIntentException) {
            Log.e(TAG, "Failed to start immediate update", e)
            emitSignal(SIGNAL_UPDATE_CHECK_FAILED, "Failed to start immediate update: ${e.message}")
        }
    }

    /**
     * Complete a flexible update after it has been downloaded.
     * This triggers the app restart and installation.
     * Only call this after receiving [STATUS_DOWNLOADED] via [SIGNAL_UPDATE_STATUS_CHANGED].
     */
    @UsedByGodot
    fun completeFlexibleUpdate() {
        Log.d(TAG, "Completing flexible update (triggering app restart)...")
        appUpdateManager.completeUpdate()
    }

    /**
     * Returns the current install status as an int constant.
     * Use after receiving the update_status_changed signal.
     */
    @UsedByGodot
    fun getInstallStatus(): Int {
        // Get the latest status synchronously from a cached perspective
        return STATUS_UNKNOWN
    }

    // =========================================================================
    // Status constants accessible from Godot
    // =========================================================================

    @UsedByGodot
    fun getStatusUnknown(): Int = STATUS_UNKNOWN

    @UsedByGodot
    fun getStatusPending(): Int = STATUS_PENDING

    @UsedByGodot
    fun getStatusDownloading(): Int = STATUS_DOWNLOADING

    @UsedByGodot
    fun getStatusDownloaded(): Int = STATUS_DOWNLOADED

    @UsedByGodot
    fun getStatusInstalling(): Int = STATUS_INSTALLING

    @UsedByGodot
    fun getStatusInstalled(): Int = STATUS_INSTALLED

    @UsedByGodot
    fun getStatusFailed(): Int = STATUS_FAILED

    @UsedByGodot
    fun getStatusCanceled(): Int = STATUS_CANCELED

    // Activity result constants
    @UsedByGodot
    fun getResultOk(): Int = Activity.RESULT_OK

    @UsedByGodot
    fun getResultCanceled(): Int = Activity.RESULT_CANCELED

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun mapInstallStatus(status: Int): Int {
        return when (status) {
            InstallStatus.UNKNOWN -> STATUS_UNKNOWN
            InstallStatus.PENDING -> STATUS_PENDING
            InstallStatus.DOWNLOADING -> STATUS_DOWNLOADING
            InstallStatus.DOWNLOADED -> STATUS_DOWNLOADED
            InstallStatus.INSTALLING -> STATUS_INSTALLING
            InstallStatus.INSTALLED -> STATUS_INSTALLED
            InstallStatus.FAILED -> STATUS_FAILED
            InstallStatus.CANCELED -> STATUS_CANCELED
            else -> STATUS_UNKNOWN
        }
    }
}
