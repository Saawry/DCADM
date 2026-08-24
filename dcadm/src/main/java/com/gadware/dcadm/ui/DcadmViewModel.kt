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
import androidx.work.WorkInfo
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
    val shouldNavigateToLogin: Boolean = false,
    val toastMessage: String? = null,

    // Backup-specific state (preserved from BackupUiState)
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val success: Boolean = false,
    val restoreSuccess: Boolean = false,
    val restoreStatusText: String = "",
    val restoreError: String? = null,

    // Local DB Export/Import & Advance Options state
    val isExportingLocal: Boolean = false,
    val isImportingLocal: Boolean = false,
    val localExportSuccess: Boolean = false,
    val localImportSuccess: Boolean = false,
    val localOperationMessage: String? = null,
    val databaseStats: com.gadware.dcadm.data.DatabaseStats? = null,
    val integrityCheckResult: String? = null,
    val isIntegrityChecking: Boolean = false
)

class DcadmViewModel(
    private val applicationContext: Context,
    private val authManager: GoogleAuthManager,
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val workManager: WorkManager,
    private val backupRepository: BackupRepository? = null
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

    fun loadSettings(forceRefresh: Boolean = false) {
        val userEmail = sessionManager.getUserEmail()
            ?: userRepository.getUserEmail()
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email

        val uid = userRepository.getUserId()
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

        if (uid.isNullOrBlank() || userEmail.isNullOrBlank()) {
            DcadmLog.e("DcadmViewModel", "No logged in user email found in loadSettings. Signing out.")
            viewModelScope.launch {
                authManager.signOut()
                sessionManager.clearSession()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        shouldNavigateToLogin = true
                    )
                }
            }
            return
        }

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

        viewModelScope.launch {
            val profile = userRepository.getUserProfile(uid, applicationContext, forceRefresh)
            val resolvedDriveEmail = profile?.driveEmail?.takeIf { it.isNotBlank() }
                ?: userEmail

            _uiState.update {
                it.copy(
                    driveEmail = resolvedDriveEmail,
                    userProfile = profile,
                    isLoading = false
                )
            }

            if (profile != null && profile.driveEmail.isBlank() && !resolvedDriveEmail.isNullOrBlank()) {
                userRepository.updateDriveEmail(uid, resolvedDriveEmail, applicationContext)
            }
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

    fun authDriveMail(activity: Activity) {
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
                val exception = result.exceptionOrNull()

                if (exception is AuthResolutionRequiredException) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showAuthResolution = true,
                            authResolutionIntent = exception.pendingIntent
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = exception?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                        )
                    }
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusText = applicationContext.getString(R.string.dcadm_backup_ready)
                )
            }
        }
    }

    fun onManualBackupClicked(activity: Activity) {
        viewModelScope.launch {
            val gdbStatus = RemoteConfigManager.getGDBSStatus()
            val uid = userRepository.getUserId() ?: run {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        shouldNavigateToLogin = true,
                        error = applicationContext.getString(R.string.dcadm_error_no_user_logged_in)
                    )
                }
                return@launch
            }
            val regStatus = userRepository.isUserRegistered(uid)
            if (gdbStatus != "not_running" && !regStatus) {
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
                val exception = result.exceptionOrNull()

                if (exception is AuthResolutionRequiredException) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showAuthResolution = true,
                            authResolutionIntent = exception.pendingIntent
                        )
                    }
                } else {
                    val err = exception?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = err,
                            toastMessage = err
                        )
                    }
                }

                return@launch
            }

            val (_, driveEmail, token) = result.getOrThrow()

            enqueueWorker(action, driveEmail, token)
        }
    }

    fun onToastShown() {
        _uiState.update { it.copy(toastMessage = null) }
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
                    isLoading = true,
                    isBackingUp = true,
                    statusText = applicationContext.getString(R.string.dcadm_backup_enqueued_background)
                )
            }
            viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(request.id).collect { workInfo ->
                    if (workInfo == null) return@collect
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            loadSettings(forceRefresh = true)
                            val successMsg = applicationContext.getString(R.string.dcadm_backup_success)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isBackingUp = false,
                                    statusText = successMsg,
                                    toastMessage = successMsg
                                )
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errorDetail = workInfo.outputData.getString("error")
                                ?: workInfo.outputData.getString("reason")
                                ?: applicationContext.getString(R.string.dcadm_error_unknown)
                            val failMsg = applicationContext.getString(R.string.dcadm_backup_failed, errorDetail)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isBackingUp = false,
                                    error = errorDetail,
                                    statusText = failMsg,
                                    toastMessage = failMsg
                                )
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isBackingUp = false,
                                    toastMessage = "Backup cancelled"
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        } else {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(inputData).build()
            workManager.enqueue(request)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isRestoring = true,
                    statusText = applicationContext.getString(R.string.dcadm_restore_enqueued_background)
                )
            }
            viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(request.id).collect { workInfo ->
                    if (workInfo == null) return@collect
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            loadSettings(forceRefresh = true)
                            val successMsg = applicationContext.getString(R.string.dcadm_restore_success)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRestoring = false,
                                    statusText = successMsg,
                                    toastMessage = successMsg
                                )
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val errorDetail = workInfo.outputData.getString("error")
                                ?: workInfo.outputData.getString("reason")
                                ?: applicationContext.getString(R.string.dcadm_error_unknown)
                            val failMsg = applicationContext.getString(R.string.dcadm_restore_failed, errorDetail)
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRestoring = false,
                                    error = errorDetail,
                                    statusText = failMsg,
                                    toastMessage = failMsg
                                )
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRestoring = false,
                                    toastMessage = "Restore cancelled"
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private suspend fun prepareDriveAccess(
        activity: Activity,
        action: PendingAction
    ): Result<Triple<String, String, String>> {
        val userEmail = sessionManager.getUserEmail()
            ?: userRepository.getUserEmail()
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email

        val uid = userRepository.getUserId()
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

        if (userEmail.isNullOrBlank() || uid.isNullOrBlank()) {
            DcadmLog.e("DcadmViewModel", "Unable to obtain logged in Gmail/UID. Signing out and navigating to login.")
            authManager.signOut()
            sessionManager.clearSession()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    shouldNavigateToLogin = true,
                    error = applicationContext.getString(R.string.dcadm_error_no_user_logged_in)
                )
            }
            return Result.failure(Exception(applicationContext.getString(R.string.dcadm_error_no_user_logged_in)))
        }

        var driveEmail = _uiState.value.driveEmail?.takeIf { it.isNotBlank() }
            ?: sessionManager.getUserProfile()?.driveEmail?.takeIf { it.isNotBlank() }
            ?: userEmail

        if (_uiState.value.driveEmail.isNullOrBlank()) {
            _uiState.update { it.copy(driveEmail = driveEmail) }
            userRepository.updateDriveEmail(uid, driveEmail, applicationContext)
        }

        // 1. Try using saved token from session manager and validate it
        val savedToken = sessionManager.getDriveToken()
        if (!savedToken.isNullOrBlank()) {
            DcadmLog.d("DcadmViewModel", "Attempting to use saved token for $driveEmail")
            try {
                val driveHelper = DriveServiceHelper(applicationContext, savedToken)
                driveHelper.findBackupFile()
                DcadmLog.d("DcadmViewModel", "Saved token is valid. Proceeding with operation.")
                return Result.success(Triple(userEmail, driveEmail, savedToken))
            } catch (e: Exception) {
                DcadmLog.d("DcadmViewModel", "Saved token invalid or expired: ${e.message}. Attempting silent renewal.")
            }
        } else {
            DcadmLog.d("DcadmViewModel", "No saved token found for $driveEmail. Attempting silent renewal.")
        }

        // 2. Attempt silent token acquisition first (targeted exclusively to logged-in user email)
        val silentResult = authManager.silentDriveAccess(applicationContext, driveEmail)
        if (silentResult.isSuccess) {
            val token = silentResult.getOrThrow()
            DcadmLog.d("DcadmViewModel", "Silent token acquired and saved for $driveEmail")
            return Result.success(Triple(userEmail, driveEmail, token))
        }

        // 3. If silent auth cannot resolve automatically, request drive access with Activity targeting strictly driveEmail
        DcadmLog.d("DcadmViewModel", "Silent auth not possible, prompting user to re-authorize for logged-in email: $driveEmail")
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
            ?: sessionManager.getUserEmail()
            ?: userRepository.getUserEmail()

        _uiState.update {
            it.copy(
                showAuthResolution = false,
                authResolutionIntent = null,
                pendingAction = null
            )
        }

        if (resultOk && driveEmail != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val authResult = authManager.requestDriveAccess(activity, driveEmail)

                if (authResult.isSuccess) {
                    val token = authResult.getOrThrow()
                    if (action != null && action != PendingAction.NONE) {
                        enqueueWorker(action, driveEmail, token)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                driveEmail = driveEmail,
                                statusText = applicationContext.getString(R.string.dcadm_backup_ready)
                            )
                        }
                    }
                } else {
                    val err = authResult.exceptionOrNull()?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = err,
                            toastMessage = err
                        )
                    }
                }
            }
        } else {
            val permDeniedMsg = applicationContext.getString(R.string.dcadm_error_permission_denied)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isBackingUp = false,
                    isRestoring = false,
                    error = permDeniedMsg,
                    toastMessage = permDeniedMsg
                )
            }
        }
    }

    // --- Local DB Export & Import ---

    fun loadDatabaseStats() {
        if (backupRepository == null) return
        val stats = backupRepository.getDatabaseStats()
        _uiState.update { it.copy(databaseStats = stats) }
    }

    fun exportLocalDatabase(destinationUri: android.net.Uri) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExportingLocal = true,
                    localExportSuccess = false,
                    localOperationMessage = null,
                    error = null
                )
            }

            val result = repo.exportDatabaseToUri(destinationUri)
            if (result.isSuccess) {
                val stats = repo.getDatabaseStats()
                _uiState.update {
                    it.copy(
                        isExportingLocal = false,
                        localExportSuccess = true,
                        databaseStats = stats,
                        localOperationMessage = applicationContext.getString(R.string.dcadm_local_export_success)
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: applicationContext.getString(R.string.dcadm_error_unknown)
                _uiState.update {
                    it.copy(
                        isExportingLocal = false,
                        localExportSuccess = false,
                        error = err,
                        localOperationMessage = err
                    )
                }
            }
        }
    }

    fun importLocalDatabase(sourceUri: android.net.Uri) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingLocal = true,
                    localImportSuccess = false,
                    localOperationMessage = null,
                    error = null
                )
            }

            val result = repo.importDatabaseFromUri(sourceUri)
            if (result.isSuccess && result.getOrNull() == true) {
                val stats = repo.getDatabaseStats()
                _uiState.update {
                    it.copy(
                        isImportingLocal = false,
                        localImportSuccess = true,
                        databaseStats = stats,
                        localOperationMessage = applicationContext.getString(R.string.dcadm_local_import_success)
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: applicationContext.getString(R.string.dcadm_local_import_failed)
                _uiState.update {
                    it.copy(
                        isImportingLocal = false,
                        localImportSuccess = false,
                        error = err,
                        localOperationMessage = err
                    )
                }
            }
        }
    }

    fun runIntegrityCheck() {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isIntegrityChecking = true, integrityCheckResult = null) }
            val result = repo.performIntegrityCheck()
            val stats = repo.getDatabaseStats()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isIntegrityChecking = false,
                        databaseStats = stats,
                        integrityCheckResult = applicationContext.getString(
                            R.string.dcadm_advance_integrity_success,
                            result.getOrNull() ?: "ok"
                        )
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isIntegrityChecking = false,
                        databaseStats = stats,
                        integrityCheckResult = applicationContext.getString(
                            R.string.dcadm_advance_integrity_error,
                            result.exceptionOrNull()?.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun createShareableBackup(onReady: (java.io.File?) -> Unit) {
        val repo = backupRepository ?: run {
            onReady(null)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingLocal = true) }
            val result = repo.createLocalBackupZipFile()
            _uiState.update { it.copy(isExportingLocal = false) }
            onReady(result.getOrNull())
        }
    }

    suspend fun checkIfDatabaseEmpty(): Boolean {
        return backupRepository?.isDatabaseEmpty() ?: true
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
            return DcadmViewModel(
                context.applicationContext,
                authManager,
                userRepository,
                sessionManager,
                workManager,
                repository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
