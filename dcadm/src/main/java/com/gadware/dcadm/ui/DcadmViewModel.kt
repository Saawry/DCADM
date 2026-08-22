package com.gadware.dcadm.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.RoomDatabase
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gadware.dcadm.R
import com.gadware.dcadm.RemoteConfigManager
import com.gadware.dcadm.auth.AuthResolutionRequiredException
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.BackupRepository
import com.gadware.dcadm.drive.DriveServiceHelper
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserProfile
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.utils.DcadmLog
import com.gadware.dcadm.worker.BackupWorker
import com.gadware.dcadm.worker.RestoreWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class PendingAction { BACKUP, RESTORE, NONE }

data class DcadmUiState(
    // Common state
    val userEmail: String? = null,
    val driveEmail: String? = null,
    val userProfile: UserProfile? = null,
    val isLoading: Boolean = false,
    val statusText: String = "",
    val error: String? = null,
    val authResolutionIntent: PendingIntent? = null,
    val pendingAction: PendingAction? = null,

    // Settings-specific state
    val lastBackupDateStr: String = "",
    val routineConfig: String = "Never",
    val selectedRoutineConfig: String = "Never",
    val showAuthResolution: Boolean = false,

    // Backup-specific state (preserved from BackupUiState)
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val success: Boolean = false,
    val restoreSuccess: Boolean = false,
    val restoreStatusText: String = "",
    val restoreError: String? = null
)

class DcadmViewModel(
    private val applicationContext: Context,
    private val authManager: GoogleAuthManager,
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DcadmUiState(
            lastBackupDateStr = applicationContext.getString(R.string.dcadm_settings_last_backup_never)
        )
    )
    val uiState: StateFlow<DcadmUiState> = _uiState

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val userEmail = sessionManager.getUserEmail()
        val lastTimestamp = sessionManager.getLastBackupDate()
        val routineConfig = sessionManager.getRoutineBackupConfig()

        val dateStr = if (lastTimestamp > 0) {
            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                .format(Date(lastTimestamp))
        } else {
            applicationContext.getString(R.string.dcadm_settings_last_backup_never)
        }

        _uiState.update {
            it.copy(
                userEmail = userEmail,
                lastBackupDateStr = dateStr,
                routineConfig = routineConfig,
                selectedRoutineConfig = routineConfig,
                isLoading = true
            )
        }

        val uid = userRepository.getUserId()
        if (uid != null) {
            viewModelScope.launch {
                val profile = userRepository.getUserProfile(uid, applicationContext)
                _uiState.update {
                    it.copy(
                        driveEmail = profile?.driveEmail,
                        userProfile = profile,
                        isLoading = false
                    )
                }
            }
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // --- Routine Config ---
    fun onRoutineConfigChanged(newConfig: String) {
        _uiState.update { it.copy(selectedRoutineConfig = newConfig) }
    }

    fun saveRoutineConfig() {
        val newConfig = _uiState.value.selectedRoutineConfig
        sessionManager.saveRoutineBackupConfig(newConfig)
        _uiState.update {
            it.copy(
                routineConfig = newConfig,
                statusText = applicationContext.getString(R.string.dcadm_backup_schedule_saved)
            )
        }
        scheduleRoutineBackup(newConfig)
    }

    private fun scheduleRoutineBackup(config: String) {
        val workName = "routine_backup"
        if (config == "Never") {
            workManager.cancelUniqueWork(workName)
            return
        }

        val driveEmail = _uiState.value.driveEmail
            ?: sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserEmail()

        val inputDataBuilder = Data.Builder()
        if (!driveEmail.isNullOrBlank()) {
            inputDataBuilder.putString("DRIVE_EMAIL", driveEmail)
        }
        val inputData = inputDataBuilder.build()

        val repeatInterval = when (config) {
            "Daily" -> 1L to TimeUnit.DAYS
            "Weekly" -> 7L to TimeUnit.DAYS
            "Monthly" -> 30L to TimeUnit.DAYS
            else -> return
        }

        val request = PeriodicWorkRequestBuilder<BackupWorker>(repeatInterval.first, repeatInterval.second)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun authDriveMail(activity: Activity){
        viewModelScope.launch {

            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    statusText = applicationContext.getString(R.string.dcadm_backup_checking_credentials)
                )
            }

            val result = prepareDriveAccess(activity, PendingAction.NONE)

            if (result.isFailure) {
                // ⚠️ If it's auth resolution, UI will handle it automatically
                val exception = result.exceptionOrNull()

                if (exception !is AuthResolutionRequiredException) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = exception?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                        )
                    }
                }

                return@launch
            }


        }
    }

    fun onManualBackupClicked(activity: Activity) {
        //checkAllStatus like remote config status, user reg status
        viewModelScope.launch {
            val gdbStatus = RemoteConfigManager.getGDBSStatus()
            val uid = userRepository.getUserId() ?: run {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = applicationContext.getString(R.string.dcadm_error_no_user_logged_in)
                    )
                }
                return@launch
            }
            val regStatus = userRepository.isUserRegistered(uid)
            if (gdbStatus != "not_running" && !regStatus) {//cross check
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = applicationContext.getString(R.string.dcadm_backup_failed_contact_support)
                    )
                }
            } else {
                handleDriveAction(activity, PendingAction.BACKUP)
            }
        }

    }

    fun onManualRestoreClicked(activity: Activity) {
        handleDriveAction(activity, PendingAction.RESTORE)
    }
    fun handleDriveAction(activity: Activity, action: PendingAction) {
        viewModelScope.launch {

            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    statusText = applicationContext.getString(R.string.dcadm_backup_checking_credentials),
                    pendingAction = action
                )
            }

            val result = prepareDriveAccess(activity, action)

            if (result.isFailure) {
                // ⚠️ If it's auth resolution, UI will handle it automatically
                val exception = result.exceptionOrNull()

                if (exception !is AuthResolutionRequiredException) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = exception?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                        )
                    }
                }

                return@launch
            }

            val (_, driveEmail, token) = result.getOrThrow()

            enqueueWorker(action, driveEmail, token)
        }
    }

    private fun enqueueWorker(action: PendingAction, driveEmail: String, accessToken: String) {
        val inputData = Data.Builder()
            .putString("DRIVE_EMAIL", driveEmail)
            .putString("ACCESS_TOKEN", accessToken)
            .build()

        if (action == PendingAction.BACKUP) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>().setInputData(inputData).build()
            workManager.enqueue(request)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusText = applicationContext.getString(R.string.dcadm_backup_enqueued_background)
                )
            }
        } else {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(inputData).build()
            workManager.enqueue(request)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusText = applicationContext.getString(R.string.dcadm_restore_enqueued_background)
                )
            }
        }
    }

    private suspend fun prepareDriveAccess(
        activity: Activity,
        action: PendingAction
    ): Result<Triple<String, String, String>> {
        val userEmail = sessionManager.getUserEmail()
            ?: return Result.failure(Exception(applicationContext.getString(R.string.dcadm_error_no_user_logged_in)))

        val uid = userRepository.getUserId()
            ?: return Result.failure(Exception(applicationContext.getString(R.string.dcadm_error_no_user_logged_in)))

        var driveEmail = _uiState.value.driveEmail
        if (driveEmail.isNullOrBlank()) {
            DcadmLog.d("DcadmViewModel", "Drive email missing, signing in first...")
            val signInResult = authManager.signIn(activity)
            if (signInResult.isFailure) {
                return Result.failure(
                    Exception(
                        signInResult.exceptionOrNull()?.message
                            ?: applicationContext.getString(R.string.dcadm_error_unknown)
                    )
                )
            }

            val firebaseUser = signInResult.getOrThrow()
            val email = firebaseUser.email
            if (email.isNullOrBlank()) {
                return Result.failure(Exception("Failed to retrieve email from Google Account."))
            }
            driveEmail = email
            userRepository.updateDriveEmail(uid, email, applicationContext)
            _uiState.update { it.copy(driveEmail = email) }
        }

        // 1. Try using saved token from session manager
        val savedToken = sessionManager.getDriveToken()
        if (!savedToken.isNullOrBlank()) {
            DcadmLog.d("DcadmViewModel", "Attempting to use saved token for $driveEmail")
            try {
                // Validate token by performing a lightweight check
                val driveHelper = DriveServiceHelper(applicationContext, savedToken)
                driveHelper.findBackupFile() // This throws exception if token is invalid
                DcadmLog.d("DcadmViewModel", "Saved token is valid. Proceeding with operation.")
                return Result.success(Triple(userEmail, driveEmail, savedToken))
            } catch (e: Exception) {
                DcadmLog.d("DcadmViewModel", "Saved token invalid or expired: ${e.message}. Requesting fresh access.")
            }
        } else {
            DcadmLog.d("DcadmViewModel", "No saved token found for $driveEmail")
        }

        // 2. Request fresh access if no saved token or token is invalid
        val authResult = authManager.requestDriveAccess(activity, driveEmail)
        if (authResult.isFailure) {
            val exception = authResult.exceptionOrNull()
            DcadmLog.e("DcadmViewModel", "Fresh drive access request failed: ${exception?.message}")
            return Result.failure(exception ?: Exception(applicationContext.getString(R.string.dcadm_error_unknown)))
        }

        val token = authResult.getOrThrow()
        DcadmLog.d("DcadmViewModel", "Fresh token acquired and saved for $driveEmail")
        return Result.success(Triple(userEmail, driveEmail, token))
    }



    // --- Resolution handling ---
    fun onAuthResolutionResult(resultOk: Boolean, activity: Activity) {
        val action = _uiState.value.pendingAction
        val driveEmail = _uiState.value.driveEmail

        _uiState.update {
            it.copy(
                showAuthResolution = false,
                authResolutionIntent = null,
                pendingAction = null
            )
        }

        if (resultOk && action != null && driveEmail != null) {
            viewModelScope.launch {
                val authResult = authManager.requestDriveAccess(activity, driveEmail)

                if (authResult.isSuccess) {
                    val token = authResult.getOrThrow()
                    enqueueWorker(action, driveEmail, token)
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isBackingUp = false,
                    isRestoring = false,
                    error = applicationContext.getString(R.string.dcadm_error_permission_denied)
                )
            }
        }
    }
}

class DcadmViewModelFactory(
    private val context: Context,
    private val database: RoomDatabase
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DcadmViewModel::class.java)) {
            val authManager = GoogleAuthManager(context)
            val repository = BackupRepository(context, database, authManager)
            val userRepository = UserRepository()
            val sessionManager = SessionManager(context)
            val workManager = WorkManager.getInstance(context)
            
            @Suppress("UNCHECKED_CAST")
            return DcadmViewModel(context.applicationContext, authManager, userRepository, sessionManager, workManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
