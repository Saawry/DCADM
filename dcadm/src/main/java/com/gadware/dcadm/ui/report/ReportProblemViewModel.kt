package com.gadware.dcadm.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.data.ProblemReport
import com.gadware.dcadm.data.ReportProblemRepository
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

data class ReportProblemUiState(
    val appName: String = "",
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val country: String = "Unknown",
    val featureName: String = "",
    val message: String = "",
    val isCheckingPending: Boolean = false,
    val isSubmitting: Boolean = false,
    val hasPendingReport: Boolean = false,
    val pendingReportDate: String? = null,
    val pendingReportMessage: String? = null,
    val isSubmitSuccess: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class ReportProblemViewModel(
    application: Application,
    private val reportRepository: ReportProblemRepository,
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReportProblemUiState())
    val uiState: StateFlow<ReportProblemUiState> = _uiState.asStateFlow()

    val availableFeatures: List<String>
        get() = DcadmConfig.getReportProblemCategories()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        val app = getApplication<Application>()
        val appName = DcadmConfig.getAppName()

        // 1. Resolve Current User Info
        val firebaseAuth = FirebaseAuth.getInstance()
        val currentUser = firebaseAuth.currentUser
        val email = currentUser?.email ?: sessionManager.getUserEmail() ?: ""
        val cachedProfile = sessionManager.getUserProfile()
        val name = cachedProfile?.name?.takeIf { it.isNotBlank() }
            ?: currentUser?.displayName ?: ""
        val phone = cachedProfile?.phoneNumber?.takeIf { it.isNotBlank() } ?: ""
        val initialCountry = cachedProfile?.country?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().displayCountry ?: "Unknown"

        _uiState.update {
            it.copy(
                appName = appName,
                email = email,
                name = name,
                phone = phone,
                country = initialCountry,
                featureName = availableFeatures.firstOrNull() ?: "Other"
            )
        }

        // 2. Fetch IP Geolocation & Check Pending Reports in background
        viewModelScope.launch {
            // Geolocation lookup
            val detectedCountry = reportRepository.getCountryName()
            if (!detectedCountry.isNullOrBlank()) {
                _uiState.update { it.copy(country = detectedCountry) }
            }

            // Check if user has an active pending report
            if (email.isNotBlank()) {
                _uiState.update { it.copy(isCheckingPending = true) }
                val result = reportRepository.checkPendingReport(email)
                result.onSuccess { pendingReport ->
                    if (pendingReport != null) {
                        val formattedDate = pendingReport.timestamp?.let { date ->
                            SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault()).format(date)
                        } ?: app.getString(R.string.dcadm_settings_last_backup_never)

                        _uiState.update {
                            it.copy(
                                isCheckingPending = false,
                                hasPendingReport = true,
                                pendingReportDate = formattedDate,
                                pendingReportMessage = pendingReport.message
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isCheckingPending = false, hasPendingReport = false) }
                    }
                }.onFailure {
                    _uiState.update { it.copy(isCheckingPending = false) }
                }
            }
        }
    }

    fun onNameChanged(name: String) {
        _uiState.update { it.copy(name = name, statusMessage = null, isError = false) }
    }

    fun onPhoneChanged(phone: String) {
        _uiState.update { it.copy(phone = phone, statusMessage = null, isError = false) }
    }

    fun onFeatureSelected(feature: String) {
        _uiState.update { it.copy(featureName = feature, statusMessage = null, isError = false) }
    }

    fun onMessageChanged(message: String) {
        _uiState.update { it.copy(message = message, statusMessage = null, isError = false) }
    }

    fun submitReport() {
        val state = _uiState.value
        val app = getApplication<Application>()

        if (state.hasPendingReport) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_already_pending)
                )
            }
            return
        }

        if (state.email.isBlank()) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_err_email)
                )
            }
            return
        }

        if (state.name.isBlank()) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_err_name)
                )
            }
            return
        }

        if (state.phone.isBlank()) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_err_phone)
                )
            }
            return
        }

        if (state.featureName.isBlank()) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_err_feature)
                )
            }
            return
        }

        if (state.message.isBlank()) {
            _uiState.update {
                it.copy(
                    isError = true,
                    statusMessage = app.getString(R.string.dcadm_report_err_message)
                )
            }
            return
        }

        val report = ProblemReport(
            appName = state.appName.ifBlank { DcadmConfig.getAppName() },
            country = state.country,
            email = state.email,
            featureName = state.featureName,
            message = state.message,
            name = state.name,
            phone = state.phone,
            status = "Pending",
            subject = "Report Problem"
        )

        _uiState.update { it.copy(isSubmitting = true, statusMessage = null, isError = false) }

        viewModelScope.launch {
            val result = reportRepository.submitReport(report)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        isSubmitSuccess = true,
                        statusMessage = app.getString(R.string.dcadm_report_success)
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        isError = true,
                        statusMessage = app.getString(R.string.dcadm_report_failed, error.message ?: "")
                    )
                }
            }
        }
    }
}

class ReportProblemViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReportProblemViewModel::class.java)) {
            return ReportProblemViewModel(
                application = application,
                reportRepository = ReportProblemRepository(),
                userRepository = UserRepository(),
                sessionManager = SessionManager(application)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
