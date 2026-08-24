package com.gadware.dcadm.ui.registration

import android.util.Log
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.databinding.DcadmActivityRegistrationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class RegistrationActivity : AppCompatActivity() {
    private val TAG = "Dcadm_Trace"

    private lateinit var binding: DcadmActivityRegistrationBinding
    private lateinit var viewModel: RegistrationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "RegistrationActivity: onCreate")
        super.onCreate(savedInstanceState)

        binding = DcadmActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val imeInsets = insets.getInsets(
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
            )
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                imeInsets.bottom
            )
            insets
        }

        val email = intent.getStringExtra("email") ?: ""

        // ViewModel setup
        val userRepository = UserRepository()
        val factory = RegistrationViewModelFactory(userRepository, email, application)
        viewModel = ViewModelProvider(this, factory)[RegistrationViewModel::class.java]

        setupUI()
        observeState()
    }

    private fun setupUI() {
        Log.d(TAG, "RegistrationActivity: setupUI")
        com.gadware.dcadm.DcadmConfig.loadAppLogo(this, binding.ivAppLogo)

        // Email (fixed)
        binding.etEmail.setText(viewModel.uiState.value.email)

        // Country Dropdown
        val countryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            viewModel.countries.map { it.displayName }
        )
        binding.actvCountry.setAdapter(countryAdapter)

        val initialCountry = viewModel.uiState.value.selectedCountry
        if (initialCountry != null) {
            binding.actvCountry.setText(initialCountry.displayName, false)
        }

        binding.actvCountry.setOnItemClickListener { parent, _, position, _ ->
            val selectedText = parent.getItemAtPosition(position) as? String
            val selectedCountry = viewModel.countries.find { it.displayName == selectedText }
                ?: viewModel.countries.getOrNull(position)
            if (selectedCountry != null) {
                viewModel.onCountrySelected(selectedCountry)
            }
        }

        // Text change listeners
        binding.etName.doOnTextChanged { text, _, _, _ ->
            viewModel.onNameChanged(text.toString())
        }

        binding.etPhone.doOnTextChanged { text, _, _, _ ->
            viewModel.onPhoneChanged(text.toString())
        }

        binding.etAddress.doOnTextChanged { text, _, _, _ ->
            viewModel.onAddressChanged(text.toString())
        }

        // Register button
        binding.btnRegister.setOnClickListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            viewModel.register(uid)
        }
    }

    private fun observeState() {
        Log.d(TAG, "RegistrationActivity: observeState")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->

                    // ---- Loading ----
                    binding.progressBar.visibility =
                        if (state.isRegistering) View.VISIBLE else View.GONE

                    binding.btnRegister.isEnabled = !state.isRegistering

                    // ---- Error ----
                    if (state.errorMessage != null) {
                        binding.tvError.text = state.errorMessage
                        binding.tvError.visibility = View.VISIBLE
                    } else {
                        binding.tvError.visibility = View.GONE
                    }

                    // ---- Success ----
                    if (state.registrationSuccess) {
                        onRegistrationSuccess(state.email)
                    }
                }
            }
        }
    }

    private fun onRegistrationSuccess(email: String) {
        Log.d(TAG, "RegistrationActivity: onRegistrationSuccess email=$email")
        val sessionManager = SessionManager(this)
        sessionManager.saveUserEmail(email)

        val targetClass = com.gadware.dcadm.DcadmConfig.getHomeActivityClassName()
        if (!targetClass.isNullOrBlank()) {
            try {
                val intent = Intent()
                intent.setClassName(this, targetClass)
                startActivity(intent)
                finish()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate to $targetClass", e)
            }
        }

        val intent = Intent(this, com.gadware.dcadm.ui.backup.BackupActivity::class.java)
        intent.putExtra("UserType", "NewUser")
        startActivity(intent)
        finish()
    }
}