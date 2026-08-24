package com.gadware.dcadm.ui.profile

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gadware.dcadm.R
import com.gadware.dcadm.databinding.DcadmActivityProfileBinding
import com.gadware.dcadm.ui.login.LoginActivity
import kotlinx.coroutines.launch
import java.util.Locale

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: DcadmActivityProfileBinding
    private lateinit var viewModel: ProfileViewModel

    override fun attachBaseContext(newBase: android.content.Context) {
        val sharedPref = newBase.getSharedPreferences("dcadm_locale_pref", android.content.Context.MODE_PRIVATE)
        val language = sharedPref.getString("language", "en") ?: "en"
        val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Locale.of(language)
        } else {
            Locale.forLanguageTag(language)
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DcadmActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val factory = ProfileViewModelFactory(application)
        viewModel = ViewModelProvider(this, factory)[ProfileViewModel::class.java]

        setupToolbar()
        setupUI()
        observeState()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupUI() {
        // Setup Country Dropdown
        val countryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            viewModel.countries.map { it.displayName }
        )
        binding.actvCountry.setAdapter(countryAdapter)

        binding.actvCountry.setOnItemClickListener { parent, _, position, _ ->
            val selectedText = parent.getItemAtPosition(position) as? String
            val selectedCountry = viewModel.countries.find { it.displayName == selectedText }
                ?: viewModel.countries.getOrNull(position)
            if (selectedCountry != null) {
                viewModel.onCountrySelected(selectedCountry)
            }
        }

        // Text Watchers
        binding.etName.doOnTextChanged { text, _, _, _ ->
            if (binding.etName.hasFocus()) {
                viewModel.onNameChanged(text.toString())
            }
        }

        binding.etPhone.doOnTextChanged { text, _, _, _ ->
            if (binding.etPhone.hasFocus()) {
                viewModel.onPhoneChanged(text.toString())
            }
        }

        binding.etAddress.doOnTextChanged { text, _, _, _ ->
            if (binding.etAddress.hasFocus()) {
                viewModel.onAddressChanged(text.toString())
            }
        }

        binding.etPhotoUrl.doOnTextChanged { text, _, _, _ ->
            if (binding.etPhotoUrl.hasFocus()) {
                viewModel.onPhotoUrlChanged(text.toString())
                com.gadware.dcadm.utils.ImageLoader.load(
                    binding.ivProfileAvatar,
                    text.toString(),
                    R.drawable.outline_person_24
                )
            }
        }

        // Action Buttons
        binding.btnSaveProfile.setOnClickListener {
            viewModel.saveProfile()
        }

        binding.btnSignOut.setOnClickListener {
            showSignOutDialog()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Header data & Avatar
                    binding.tvHeaderName.text = state.name.ifBlank { getString(R.string.dcadm_settings_user_name_placeholder) }
                    binding.tvHeaderEmail.text = state.email

                    com.gadware.dcadm.utils.ImageLoader.load(
                        binding.ivProfileAvatar,
                        state.photoUrl,
                        R.drawable.outline_person_24
                    )

                    val isPaid = state.userType.equals("paid", ignoreCase = true)
                    binding.chipAccountType.text = if (isPaid) "PRO / PAID" else "FREE"
                    binding.chipAccountStatus.text = state.status.uppercase(Locale.US)

                    // Fill Form Fields if not focused
                    if (!binding.etName.hasFocus() && binding.etName.text.toString() != state.name) {
                        binding.etName.setText(state.name)
                    }
                    if (!binding.etPhone.hasFocus() && binding.etPhone.text.toString() != state.phoneNumber) {
                        binding.etPhone.setText(state.phoneNumber)
                    }
                    if (!binding.etAddress.hasFocus() && binding.etAddress.text.toString() != state.address) {
                        binding.etAddress.setText(state.address)
                    }
                    if (!binding.etPhotoUrl.hasFocus() && binding.etPhotoUrl.text.toString() != state.photoUrl) {
                        binding.etPhotoUrl.setText(state.photoUrl)
                    }
                    state.selectedCountry?.let { country ->
                        if (binding.actvCountry.text.toString() != country.displayName) {
                            binding.actvCountry.setText(country.displayName, false)
                        }
                    }

                    // Progress states
                    binding.progressSave.visibility = if (state.isSaving || state.isSigningOut || state.isDeletingAccount) View.VISIBLE else View.GONE
                    binding.btnSaveProfile.isEnabled = !state.isSaving && !state.isSigningOut && !state.isDeletingAccount
                    binding.btnSignOut.isEnabled = !state.isSigningOut && !state.isDeletingAccount
                    binding.btnDeleteAccount.isEnabled = !state.isDeletingAccount && !state.isSigningOut

                    // Status Messages
                    if (!state.statusMessage.isNullOrBlank()) {
                        binding.tvProfileStatus.text = state.statusMessage
                        val statusColor = com.google.android.material.color.MaterialColors.getColor(
                            binding.tvProfileStatus,
                            if (state.isError) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
                        )
                        binding.tvProfileStatus.setTextColor(statusColor)
                        binding.tvProfileStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvProfileStatus.visibility = View.GONE
                    }

                    // Navigation on SignOut or Account Deletion
                    if (state.signOutSuccess || state.deleteAccountSuccess) {
                        if (state.deleteAccountSuccess) {
                            Toast.makeText(this@ProfileActivity, R.string.dcadm_profile_delete_success, Toast.LENGTH_SHORT).show()
                        }
                        navigateToLogin()
                    }
                }
            }
        }
    }

    private fun showSignOutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dcadm_profile_sign_out_title)
            .setMessage(R.string.dcadm_profile_sign_out_msg)
            .setPositiveButton(R.string.dcadm_profile_sign_out_confirm) { _, _ ->
                viewModel.signOut()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dcadm_profile_delete_account_title)
            .setMessage(R.string.dcadm_profile_delete_account_msg)
            .setIcon(R.drawable.baseline_delete_24)
            .setPositiveButton(R.string.dcadm_profile_delete_account_confirm) { _, _ ->
                viewModel.deleteAccount()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
