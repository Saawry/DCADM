package com.gadware.dcadm.update

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gadware.dcadm.R
import com.gadware.dcadm.utils.DcadmLog
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Mode of the in-app update flow.
 */
enum class DcadmUpdateMode {
    /**
     * Downloads update in the background while the user continues using the app.
     * When completed, prompts the user to restart the application.
     */
    FLEXIBLE,

    /**
     * Full-screen blocking UI provided by Google Play.
     * Prevents app usage until the update completes or is cancelled.
     */
    IMMEDIATE;

    internal fun toPlayType(): Int = when (this) {
        FLEXIBLE -> AppUpdateType.FLEXIBLE
        IMMEDIATE -> AppUpdateType.IMMEDIATE
    }
}

/**
 * Lifecycle-aware manager for Google Play In-App Updates.
 * Automates update discovery, installation listeners, onResume progress checks,
 * Material restart prompt Snackbars, and test mode.
 */
object DcadmUpdateManager {

    private const val TAG = "DcadmUpdate"
    const val REQUEST_CODE_IN_APP_UPDATE = 53029

    private var isTestMode: Boolean = false
    private var fakeAppUpdateManager: FakeAppUpdateManager? = null
    private var activeAppUpdateManager: AppUpdateManager? = null

    private var currentMode: DcadmUpdateMode = DcadmUpdateMode.FLEXIBLE
    private var customDownloadedCallback: (() -> Unit)? = null
    private var installStateListener: InstallStateUpdatedListener? = null

    /**
     * Enables or disables test mode using FakeAppUpdateManager.
     */
    fun setTestMode(enabled: Boolean, isUpdateAvailable: Boolean = true, isImmediate: Boolean = false) {
        this.isTestMode = enabled
        DcadmLog.d(TAG, "In-App Update test mode set to: $enabled (available: $isUpdateAvailable, immediate: $isImmediate)")
        if (enabled && fakeAppUpdateManager != null) {
            setupFakeManager(fakeAppUpdateManager!!, isUpdateAvailable, isImmediate)
        }
    }

    fun isTestMode(): Boolean = isTestMode

    fun getFakeAppUpdateManager(context: Context): FakeAppUpdateManager {
        if (fakeAppUpdateManager == null) {
            fakeAppUpdateManager = FakeAppUpdateManager(context.applicationContext).also {
                setupFakeManager(it, updateAvailable = true, immediate = false)
            }
        }
        return fakeAppUpdateManager!!
    }

    private fun setupFakeManager(manager: FakeAppUpdateManager, updateAvailable: Boolean, immediate: Boolean) {
        if (updateAvailable) {
            manager.setUpdateAvailable(2)
            if (immediate) {
                manager.setUpdatePriority(5)
            } else {
                manager.setUpdatePriority(2)
            }
        } else {
            manager.setUpdateAvailable(0)
        }
    }

    private fun getAppUpdateManager(context: Context): AppUpdateManager {
        return if (isTestMode) {
            getFakeAppUpdateManager(context).also { activeAppUpdateManager = it }
        } else {
            activeAppUpdateManager ?: AppUpdateManagerFactory.create(context.applicationContext).also {
                activeAppUpdateManager = it
            }
        }
    }

    /**
     * Checks for updates and initiates the update flow if an update is available.
     * Automatically registers lifecycle hooks to handle onResume state and cleanup on destroy.
     *
     * @param activity Foreground activity to host the update flow.
     * @param mode Update mode: [DcadmUpdateMode.FLEXIBLE] (default) or [DcadmUpdateMode.IMMEDIATE].
     * @param onUpdateDownloaded Optional custom callback when a flexible update finishes downloading.
     *                           If null, a default Material Snackbar with "Restart" action will be shown.
     * @param onCheckCompleted Optional callback with update availability result.
     */
    fun checkForUpdates(
        activity: Activity,
        mode: DcadmUpdateMode = DcadmUpdateMode.FLEXIBLE,
        onUpdateDownloaded: (() -> Unit)? = null,
        onCheckCompleted: ((isAvailable: Boolean, appUpdateInfo: AppUpdateInfo?) -> Unit)? = null
    ) {
        this.currentMode = mode
        this.customDownloadedCallback = onUpdateDownloaded

        val updateManager = getAppUpdateManager(activity)

        // Setup automated lifecycle observer if activity is a LifecycleOwner
        if (activity is LifecycleOwner) {
            attachLifecycleObserver(activity)
        }

        // Register Flexible update listener
        registerInstallListener(activity, updateManager)

        // Check for updates
        val appUpdateInfoTask = updateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            val playType = mode.toPlayType()
            val isAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val isAllowed = appUpdateInfo.isUpdateTypeAllowed(playType)

            DcadmLog.i(TAG, "Update check result: availability=${appUpdateInfo.updateAvailability()}, typeAllowed=$isAllowed, version=${appUpdateInfo.availableVersionCode()}")

            onCheckCompleted?.invoke(isAvailable && isAllowed, appUpdateInfo)

            if (isAvailable && isAllowed) {
                startUpdate(activity, updateManager, appUpdateInfo, mode)
            } else if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                notifyUpdateDownloaded(activity)
            }
        }.addOnFailureListener { e ->
            DcadmLog.e(TAG, "Failed to check for in-app updates: ${e.message}", e)
            onCheckCompleted?.invoke(false, null)
        }
    }

    private fun startUpdate(
        activity: Activity,
        updateManager: AppUpdateManager,
        appUpdateInfo: AppUpdateInfo,
        mode: DcadmUpdateMode
    ) {
        try {
            val options = AppUpdateOptions.newBuilder(mode.toPlayType()).build()
            updateManager.startUpdateFlowForResult(
                appUpdateInfo,
                activity,
                options,
                REQUEST_CODE_IN_APP_UPDATE
            )
            DcadmLog.i(TAG, "In-App update flow started with mode: $mode")

            // Simulate progress if using FakeAppUpdateManager in test mode
            if (isTestMode && updateManager is FakeAppUpdateManager) {
                simulateFakeUpdateFlow(activity, updateManager, mode)
            }
        } catch (e: Exception) {
            DcadmLog.e(TAG, "Error starting in-app update flow: ${e.message}", e)
        }
    }

    private fun simulateFakeUpdateFlow(
        activity: Activity,
        fakeManager: FakeAppUpdateManager,
        mode: DcadmUpdateMode
    ) {
        try {
            fakeManager.userAcceptsUpdate()
            if (mode == DcadmUpdateMode.FLEXIBLE) {
                fakeManager.setTotalBytesToDownload(100L)
                fakeManager.downloadStarts()
                fakeManager.setBytesDownloaded(50L)
                fakeManager.setBytesDownloaded(100L)
                fakeManager.downloadCompletes()
                notifyUpdateDownloaded(activity)
            }
        } catch (e: Exception) {
            DcadmLog.d(TAG, "Fake update simulation note: ${e.message}")
        }
    }

    private fun registerInstallListener(activity: Activity, updateManager: AppUpdateManager) {
        if (installStateListener != null) return

        installStateListener = InstallStateUpdatedListener { state: InstallState ->
            DcadmLog.d(TAG, "Install state updated: status=${state.installStatus()}, errorCode=${state.installErrorCode()}")
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                notifyUpdateDownloaded(activity)
            }
        }
        updateManager.registerListener(installStateListener!!)
    }

    /**
     * Unregisters the installation listener to avoid leaks.
     */
    fun unregisterListener() {
        val manager = activeAppUpdateManager ?: return
        installStateListener?.let {
            manager.unregisterListener(it)
            installStateListener = null
        }
    }

    /**
     * Prompts the user or invokes callback when an update is downloaded.
     */
    fun notifyUpdateDownloaded(activity: Activity) {
        if (customDownloadedCallback != null) {
            customDownloadedCallback?.invoke()
            return
        }

        val rootView = activity.findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(
            rootView,
            activity.getString(R.string.dcadm_update_downloaded),
            Snackbar.LENGTH_INDEFINITE
        ).setAction(activity.getString(R.string.dcadm_update_restart)) {
            completeUpdate()
        }.show()
    }

    /**
     * Completes the flexible in-app update by restarting the application.
     */
    fun completeUpdate() {
        DcadmLog.i(TAG, "Completing in-app update and restarting app...")
        activeAppUpdateManager?.completeUpdate()
    }

    /**
     * Resumes the update flow if interrupted while the app was in the background.
     */
    fun resumeUpdateIfNeeded(activity: Activity) {
        val updateManager = getAppUpdateManager(activity)
        updateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                notifyUpdateDownloaded(activity)
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                DcadmLog.i(TAG, "Resuming developer-triggered update in progress...")
                try {
                    val options = AppUpdateOptions.newBuilder(currentMode.toPlayType()).build()
                    updateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activity,
                        options,
                        REQUEST_CODE_IN_APP_UPDATE
                    )
                } catch (e: Exception) {
                    DcadmLog.e(TAG, "Failed to resume in-app update: ${e.message}", e)
                }
            }
        }
    }

    private fun attachLifecycleObserver(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (owner is Activity) {
                    resumeUpdateIfNeeded(owner)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                unregisterListener()
            }
        })
    }
}
