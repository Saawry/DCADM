package com.gadware.dcadm.ui.login

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.RoomDatabase
import com.gadware.dcadm.R
import com.gadware.dcadm.auth.AuthResolutionRequiredException
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.BackupRepository
import com.gadware.dcadm.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class LoadingMessage(val message: String) : LoginState()
    data class RequiresAuthResolution(val intent: PendingIntent?, val email: String) : LoginState()
    data class SuccessRegistered(val email: String) : LoginState()
    data class SuccessNotRegistered(val email: String) : LoginState()
    data class Error(val message: String) : LoginState()
}

class LoginViewModel(
    private val authManager: GoogleAuthManager,
    private val userRepository: UserRepository,
    private val backupRepository: BackupRepository,
    private val applicationContext: Context
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun isSignedIn(): Boolean {
        return userRepository.isSignedIn()
    }

    fun checkRegistrationAndNavigate() {
        viewModelScope.launch {
            val uid = userRepository.getUserId() ?: return@launch
            val email = userRepository.getUserEmail() ?: ""

            _loginState.value = LoginState.Loading

            val profile = userRepository.getUserProfile(uid, applicationContext, forceRefresh = true)
            val isRegistered = profile != null && profile.regStatus == "registered"

            if (isRegistered) {
                _loginState.value = LoginState.SuccessRegistered(email)
            } else {
                _loginState.value = LoginState.SuccessNotRegistered(email)
            }
        }
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading

            val signInResult = authManager.signIn(activity)

            if (signInResult.isFailure) {
                val errorDetail = signInResult.exceptionOrNull()?.message ?: ""
                _loginState.value = LoginState.Error(
                    applicationContext.getString(R.string.dcadm_login_failed, errorDetail)
                )
                return@launch
            }

            val user = signInResult.getOrThrow()
            val email = user.email ?: ""
            val uid = user.uid

            // Plan 1: Immediately request Google Drive (DRIVE_APPDATA) authorization during sign-in
            _loginState.value = LoginState.LoadingMessage(
                applicationContext.getString(R.string.dcadm_login_checking_drive_permissions)
            )

            val driveAuthResult = authManager.requestDriveAccess(activity, email)

            if (driveAuthResult.isFailure) {
                val exception = driveAuthResult.exceptionOrNull()
                if (exception is AuthResolutionRequiredException) {
                    _loginState.value = LoginState.RequiresAuthResolution(exception.pendingIntent, email)
                    return@launch
                }
            }

            // Drive access granted or silently resolved -> proceed with Firestore profile check
            checkRegistrationAndProceed(uid, email)
        }
    }

    fun onAuthResolutionResult(resultOk: Boolean, email: String, activity: Activity) {
        viewModelScope.launch {
            val uid = userRepository.getUserId() ?: ""
            if (resultOk) {
                // Save/refresh drive token now that user has granted consent
                authManager.requestDriveAccess(activity, email)
            }
            checkRegistrationAndProceed(uid, email)
        }
    }

    private suspend fun checkRegistrationAndProceed(uid: String, email: String) {
        _loginState.value = LoginState.Loading
        val profile = userRepository.getUserProfile(uid, applicationContext, forceRefresh = true)
        val isRegistered = profile != null && profile.regStatus == "registered"

        // Sync FCM device token with Firestore and SessionManager & sync topic subscriptions
        com.gadware.dcadm.notification.DcadmNotificationManager.syncDeviceToken(applicationContext)
        com.gadware.dcadm.notification.DcadmNotificationManager.syncTopics(applicationContext, profile)

        if (isRegistered) {
            _loginState.value = LoginState.SuccessRegistered(email)
        } else {
            _loginState.value = LoginState.SuccessNotRegistered(email)
        }
    }
}

class LoginViewModelFactory(
    private val context: Context,
    private val database: RoomDatabase
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            val authManager = GoogleAuthManager(context)
            val userRepository = UserRepository()
            val backupRepository = BackupRepository(context, database, authManager)

            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(authManager, userRepository, backupRepository, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
