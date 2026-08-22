package com.gadware.dcadm.ui.registration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gadware.dcadm.R
import com.gadware.dcadm.data.Country
import com.gadware.dcadm.data.CountryData
import com.gadware.dcadm.data.UserProfile
import com.gadware.dcadm.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class RegistrationUiState(
    val email: String = "",
    val name: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val selectedCountry: Country? = null,
    val isRegistering: Boolean = false,
    val registrationSuccess: Boolean = false,
    val errorMessage: String? = null
)

class RegistrationViewModel(
    private val userRepository: UserRepository,
    val email: String,
    application: Application
) : AndroidViewModel(application) {

    val countries: List<Country> = CountryData.countries

    private val _uiState = MutableStateFlow(
        RegistrationUiState(
            email = email,
            selectedCountry = CountryData.getDefaultCountry()
        )
    )
    val uiState: StateFlow<RegistrationUiState> = _uiState

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    fun onCountrySelected(country: Country) {
        _uiState.update { it.copy(selectedCountry = country) }
    }

    fun onPhoneChanged(value: String) {
        _uiState.update { it.copy(phoneNumber = value) }
    }

    fun onAddressChanged(value: String) {
        _uiState.update { it.copy(address = value) }
    }

    fun checkExistingRegistration() {
        viewModelScope.launch {
            val uid = userRepository.getUserId() ?: return@launch
            val profile = userRepository.getUserProfile(uid, getApplication(), forceRefresh = true)
            if (profile != null && profile.regStatus == "registered") {
                _uiState.update { it.copy(registrationSuccess = true) }
            }
        }
    }

    fun register(uid: String) {
        val state = _uiState.value

        if (state.name.isBlank() ||
            state.phoneNumber.isBlank() ||
            state.address.isBlank() ||
            state.selectedCountry == null
        ) {
            _uiState.update {
                it.copy(errorMessage = getApplication<Application>().getString(R.string.dcadm_registration_fill_all_fields))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRegistering = true,
                    errorMessage = null
                )
            }

            val calendar = Calendar.getInstance()
            val regDate = calendar.timeInMillis
            calendar.add(Calendar.MONTH, 2)
            val nextPayDate = calendar.timeInMillis

            val formattedPhoneNumber = constructPhoneNumber(
                state.selectedCountry.dialCode,
                state.phoneNumber
            )

            val userProfile = UserProfile(
                email = state.email,
                name = state.name.trim(),
                phoneNumber = formattedPhoneNumber,
                address = state.address.trim(),
                country = state.selectedCountry.name,
                userType = "free",
                status = "active",
                regDate = regDate,
                regStatus = "registered",
                userId = uid,
                validTill = nextPayDate,
                deviceToken = null,
                lastActiveDate = regDate
            )

            val result = userRepository.registerUser(userProfile, getApplication())

            if (result.isSuccess) {
                com.gadware.dcadm.data.SessionManager(getApplication()).saveRegStatus()
                _uiState.update {
                    it.copy(
                        isRegistering = false,
                        registrationSuccess = true
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isRegistering = false,
                        errorMessage = result.exceptionOrNull()?.message
                            ?: getApplication<Application>().getString(R.string.dcadm_registration_failed)
                    )
                }
            }
        }
    }

    private fun constructPhoneNumber(dialCode: String, rawPhone: String): String {
        val cleanNumber = rawPhone.trim().replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) return ""

        val cleanDialCode = dialCode.trim().replace(Regex("[^0-9+]"), "")
        val dialDigits = cleanDialCode.removePrefix("+")

        return when {
            cleanNumber.startsWith("+") -> cleanNumber
            cleanNumber.startsWith(dialDigits) -> "+$cleanNumber"
            else -> {
                val digitsWithoutLeadingZero = cleanNumber.trimStart('0')
                "$cleanDialCode$digitsWithoutLeadingZero"
            }
        }
    }
}

class RegistrationViewModelFactory(
    private val userRepository: UserRepository,
    private val email: String,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RegistrationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RegistrationViewModel(userRepository, email, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
