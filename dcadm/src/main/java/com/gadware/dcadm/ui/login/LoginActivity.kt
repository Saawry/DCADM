package com.gadware.dcadm.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.databinding.DcadmActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private val TAG = "Dcadm_Trace"

    private lateinit var binding: DcadmActivityLoginBinding
    private lateinit var viewModel: LoginViewModel

    private lateinit var intentLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "LoginActivity: onCreate")
        super.onCreate(savedInstanceState)

        binding = DcadmActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val factory = LoginViewModelFactory(this, DcadmConfig.getDatabase(this))
        viewModel = ViewModelProvider(this, factory)[LoginViewModel::class.java]

        setupLauncher()
        setupUI()
        observeState()

        if (viewModel.isSignedIn()) {
            viewModel.checkRegistrationAndNavigate()
        }
    }

    override fun onStart() {
        Log.d(TAG, "LoginActivity: onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.d(TAG, "LoginActivity: onResume")
        super.onResume()
    }

    override fun onPause() {
        Log.d(TAG, "LoginActivity: onPause")
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "LoginActivity: onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "LoginActivity: onDestroy")
        super.onDestroy()
    }

    private fun setupLauncher() {
        intentLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val currentState = viewModel.loginState.value
                if (currentState is LoginState.RequiresAuthResolution) {
                    viewModel.onAuthResolutionResult(
                        result.resultCode == RESULT_OK,
                        currentState.email,
                        this
                    )
                }
            }
    }

    private fun setupUI() {
        Log.d(TAG, "LoginActivity: setupUI")
        DcadmConfig.loadAppLogo(this, binding.appLogo)
        binding.appTitleTv.text = DcadmConfig.getAppName()

        // Terms & Privacy Configuration
        val termsUrl = DcadmConfig.getTermsAndPrivacyUrl()
        if (termsUrl.isNullOrBlank()) {
            binding.termsLayout.visibility = View.GONE
            binding.btnLogin.isEnabled = true
        } else {
            binding.termsLayout.visibility = View.VISIBLE
            binding.btnLogin.isEnabled = false
            binding.cbTerms.setOnCheckedChangeListener { _, isChecked ->
                binding.btnLogin.isEnabled = isChecked
            }
            binding.tvTerms.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(termsUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open terms URL", e)
                }
            }
        }

        // Branding Ribbon Footer Configuration
        if (DcadmConfig.isBrandingFooterEnabled()) {
            binding.brandingCard.visibility = View.VISIBLE
            DcadmConfig.getCompanyName()?.let { binding.tvCompanyName.text = it }
            DcadmConfig.getCompanyUrl()?.let { binding.tvCompanyUrl.text = it }
            val companyLogo = DcadmConfig.getCompanyLogoResId()
            if (companyLogo != null && companyLogo != 0) {
                binding.ivCompanyLogo.setImageResource(companyLogo)
                binding.ivCompanyLogo.visibility = View.VISIBLE
            } else {
                binding.ivCompanyLogo.visibility = View.GONE
            }
        } else {
            binding.brandingCard.visibility = View.GONE
        }

        binding.btnLogin.setOnClickListener {
            if (termsUrl.isNullOrBlank() || binding.cbTerms.isChecked) {
                viewModel.signIn(this)
            } else {
                Toast.makeText(this, R.string.dcadm_login_agree_terms_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        Log.d(TAG, "LoginActivity: observeState")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { state ->
                    // ---- ERROR ----
                    if (state is LoginState.Error) {
                        binding.tvStatus.text = state.message
                        binding.tvStatus.setTextColor(android.graphics.Color.RED)
                        binding.tvStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.visibility = View.GONE
                    }

                    // ---- LOADING ----
                    val isLoading = state is LoginState.Loading || state is LoginState.LoadingMessage
                    binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE

                    val termsAccepted = DcadmConfig.getTermsAndPrivacyUrl().isNullOrBlank() || binding.cbTerms.isChecked
                    binding.btnLogin.isEnabled = !isLoading && termsAccepted

                    if (state is LoginState.LoadingMessage) {
                        binding.tvStatus.text = state.message
                    }

                    // ---- NAVIGATION ----
                    when (state) {
                        is LoginState.SuccessRegistered -> {
                            navigateToHome()
                        }
                        is LoginState.SuccessNotRegistered -> {
                            navigateToRegistration(state.email)
                        }
                        is LoginState.RequiresAuthResolution -> {
                            state.intent?.let { pendingIntent ->
                                val request = IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()
                                intentLauncher.launch(request)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun navigateToHome() {
        Log.d(TAG, "LoginActivity: navigateToHome")
        val targetClass = DcadmConfig.getHomeActivityClassName()
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

        // Fallback to BackupActivity if host activity class is not specified or not found
        val intent = Intent(this, com.gadware.dcadm.ui.backup.BackupActivity::class.java)
        intent.putExtra("UserType", "OldUser")
        startActivity(intent)
        finish()
    }

    private fun navigateToRegistration(email: String) {
        Log.d(TAG, "LoginActivity: navigateToRegistration email=$email")
        val intent = Intent(this, com.gadware.dcadm.ui.registration.RegistrationActivity::class.java)
        intent.putExtra("email", email)
        startActivity(intent)
        finish()
    }
}