package com.gadware.dcadm.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gadware.dcadm.R
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.Country
import com.gadware.dcadm.data.CountryData
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserProfile
import com.gadware.dcadm.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val country: String = "",
    val selectedCountry: Country? = null,
    val userType: String = "free",
    val status: String = "active",
    val photoUrl: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSigningOut: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val saveSuccess: Boolean = false,
    val signOutSuccess: Boolean = false,
    val deleteAccountSuccess: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class ProfileViewModel(
    application: Application,
    private val userRepository: UserRepository,
    private val authManager: GoogleAuthManager,
    private val sessionManager: SessionManager
) : AndroidViewModel(application) {

    val countries: List<Country> = CountryData.countries

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    private var currentProfile: UserProfile? = null

    init {
        loadProfile()
    }

    fun loadProfile(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }

            val uid = userRepository.getUserId() ?: sessionManager.getUserProfile()?.userId ?: ""
            val email = userRepository.getUserEmail() ?: sessionManager.getUserEmail() ?: ""
            val googlePhotoUrl = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl?.toString() ?: ""

            val profile = if (uid.isNotBlank() && uid != "pending") {
                userRepository.getUserProfile(uid, getApplication(), forceRefresh)
            } else {
                sessionManager.getUserProfile()
            }

            currentProfile = profile

            if (profile != null) {
                val matchedCountry = CountryData.findByNameOrCode(profile.country)
                    ?: CountryData.getDefaultCountry()

                // Extract pure phone without dial code if dialCode is prefixed
                val rawPhone = if (matchedCountry.dialCode.isNotBlank() && profile.phoneNumber.startsWith(matchedCountry.dialCode)) {
                    profile.phoneNumber.removePrefix(matchedCountry.dialCode).trim()
                } else {
                    profile.phoneNumber
                }

                val resolvedPhoto = profile.photoUrl.ifBlank { googlePhotoUrl }

                _uiState.update {
                    it.copy(
                        name = profile.name,
                        email = profile.email.ifBlank { email },
                        phoneNumber = rawPhone,
                        address = profile.address,
                        country = profile.country.ifBlank { matchedCountry.name },
                        selectedCountry = matchedCountry,
                        userType = profile.userType,
                        status = profile.status,
                        photoUrl = resolvedPhoto,
                        isLoading = false
                    )
                }
            } else {
                val defaultCountry = CountryData.getDefaultCountry()
                _uiState.update {
                    it.copy(
                        email = email,
                        selectedCountry = defaultCountry,
                        country = defaultCountry.name,
                        photoUrl = googlePhotoUrl,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun onCountrySelected(country: Country) {
        _uiState.update { it.copy(selectedCountry = country, country = country.name) }
    }

    fun onPhoneChanged(value: String) {
        _uiState.update { it.copy(phoneNumber = value) }
    }

    fun onAddressChanged(value: String) {
        _uiState.update { it.copy(address = value) }
    }

    fun onPhotoUrlChanged(value: String) {
        _uiState.update { it.copy(photoUrl = value) }
    }

    fun saveProfile() {
        val state = _uiState.value

        if (state.name.isBlank()) {
            _uiState.update {
                it.copy(
                    statusMessage = getApplication<Application>().getString(R.string.dcadm_registration_fill_all_fields),
                    isError = true
                )
            }
            return
        }

        if (state.phoneNumber.isBlank()) {
            _uiState.update {
                it.copy(
                    statusMessage = getApplication<Application>().getString(R.string.dcadm_registration_fill_all_fields),
                    isError = true
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    statusMessage = null,
                    isError = false,
                    saveSuccess = false
                )
            }

            val dialCode = state.selectedCountry?.dialCode ?: ""
            val fullPhoneNumber = if (dialCode.isNotBlank() && !state.phoneNumber.startsWith("+")) {
                "$dialCode${state.phoneNumber.trim().removePrefix("0")}"
            } else {
                state.phoneNumber.trim()
            }

            val baseProfile = currentProfile ?: UserProfile(
                email = state.email,
                userId = userRepository.getUserId() ?: "pending"
            )

            val updatedProfile = baseProfile.copy(
                name = state.name.trim(),
                country = state.selectedCountry?.name ?: state.country,
                phoneNumber = fullPhoneNumber,
                address = state.address.trim(),
                photoUrl = state.photoUrl.trim()
            )

            val result = userRepository.updateUserProfile(updatedProfile, getApplication())

            if (result.isSuccess) {
                currentProfile = updatedProfile
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = true,
                        statusMessage = getApplication<Application>().getString(R.string.dcadm_profile_saved_success),
                        isError = false
                    )
                }
            } else {
                val error = result.exceptionOrNull()?.message
                    ?: getApplication<Application>().getString(R.string.dcadm_error_unknown)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = false,
                        statusMessage = getApplication<Application>().getString(R.string.dcadm_profile_save_failed, error),
                        isError = true
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSigningOut = true) }
            authManager.signOut()
            _uiState.update {
                it.copy(
                    isSigningOut = false,
                    signOutSuccess = true
                )
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true) }

            val uid = userRepository.getUserId() ?: ""
            if (uid.isNotBlank()) {
                userRepository.deleteUserProfile(uid)
            }

            val result = authManager.deleteAccount()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isDeletingAccount = false,
                        deleteAccountSuccess = true
                    )
                }
            } else {
                val error = result.exceptionOrNull()?.message
                    ?: getApplication<Application>().getString(R.string.dcadm_error_unknown)
                _uiState.update {
                    it.copy(
                        isDeletingAccount = false,
                        deleteAccountSuccess = false,
                        statusMessage = getApplication<Application>().getString(R.string.dcadm_profile_delete_failed, error),
                        isError = true
                    )
                }
            }
        }
    }
}

class ProfileViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            val userRepo = UserRepository()
            val authMgr = GoogleAuthManager(application)
            val sessionMgr = SessionManager(application)
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(application, userRepo, authMgr, sessionMgr) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
