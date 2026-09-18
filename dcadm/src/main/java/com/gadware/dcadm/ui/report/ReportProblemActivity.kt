package com.gadware.dcadm.ui.report

import android.content.Context
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
import com.gadware.dcadm.databinding.DcadmActivityReportProblemBinding
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import java.util.Locale

class ReportProblemActivity : AppCompatActivity() {

    private lateinit var binding: DcadmActivityReportProblemBinding
    private lateinit var viewModel: ReportProblemViewModel

    override fun attachBaseContext(newBase: Context) {
        val sharedPref = newBase.getSharedPreferences("dcadm_locale_pref", Context.MODE_PRIVATE)
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
        binding = DcadmActivityReportProblemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val factory = ReportProblemViewModelFactory(application)
        viewModel = ViewModelProvider(this, factory)[ReportProblemViewModel::class.java]

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
        // Setup Feature dropdown
        val featureAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            viewModel.availableFeatures
        )
        binding.spinnerFeature.setAdapter(featureAdapter)
        binding.spinnerFeature.setOnItemClickListener { parent, _, position, _ ->
            val selectedFeature = parent.getItemAtPosition(position) as? String
                ?: viewModel.availableFeatures.getOrNull(position)
            if (selectedFeature != null) {
                viewModel.onFeatureSelected(selectedFeature)
            }
        }

        // Text input watchers
        binding.etName.doOnTextChanged { text, _, _, _ ->
            if (binding.etName.hasFocus()) {
                viewModel.onNameChanged(text?.toString() ?: "")
            }
        }

        binding.etPhone.doOnTextChanged { text, _, _, _ ->
            if (binding.etPhone.hasFocus()) {
                viewModel.onPhoneChanged(text?.toString() ?: "")
            }
        }

        binding.etMessage.doOnTextChanged { text, _, _, _ ->
            if (binding.etMessage.hasFocus()) {
                viewModel.onMessageChanged(text?.toString() ?: "")
            }
        }

        binding.btnSubmit.setOnClickListener {
            viewModel.submitReport()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 1. Detected Country
                    binding.tvCountryBadge.text = getString(R.string.dcadm_report_country_label, state.country)

                    // 2. Pre-fill Form Fields
                    if (!binding.etEmail.hasFocus() && binding.etEmail.text.toString() != state.email) {
                        binding.etEmail.setText(state.email)
                    }

                    if (!binding.etName.hasFocus() && binding.etName.text.toString() != state.name) {
                        binding.etName.setText(state.name)
                    }

                    if (!binding.etPhone.hasFocus() && binding.etPhone.text.toString() != state.phone) {
                        binding.etPhone.setText(state.phone)
                    }

                    if (state.featureName.isNotBlank() && binding.spinnerFeature.text.toString() != state.featureName) {
                        binding.spinnerFeature.setText(state.featureName, false)
                    }

                    // 3. Pending Report Banner
                    if (state.hasPendingReport) {
                        binding.cardExistingReport.visibility = View.VISIBLE
                        val dateDesc = state.pendingReportDate ?: getString(R.string.dcadm_settings_last_backup_never)
                        binding.tvExistingReportDate.text = getString(
                            R.string.dcadm_report_pending_banner_desc,
                            dateDesc,
                            "Pending"
                        )

                        if (!state.pendingReportMessage.isNullOrBlank()) {
                            binding.tvExistingReportMessage.visibility = View.VISIBLE
                            binding.tvExistingReportMessage.text = "Issue: ${state.pendingReportMessage}"
                        } else {
                            binding.tvExistingReportMessage.visibility = View.GONE
                        }

                        // Disable submit button when pending report exists
                        binding.btnSubmit.isEnabled = false
                        binding.btnSubmit.alpha = 0.5f
                    } else {
                        binding.cardExistingReport.visibility = View.GONE
                        binding.btnSubmit.isEnabled = !state.isSubmitting
                        binding.btnSubmit.alpha = 1.0f
                    }

                    // 4. Progress Indicator
                    binding.progressSubmit.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE

                    // 5. Status / Error message
                    if (!state.statusMessage.isNullOrBlank()) {
                        binding.tvStatus.text = state.statusMessage
                        val colorAttr = if (state.isError) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary
                        val color = MaterialColors.getColor(binding.tvStatus, colorAttr)
                        binding.tvStatus.setTextColor(color)
                        binding.tvStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.visibility = View.GONE
                    }

                    // 6. Navigation on Submit Success
                    if (state.isSubmitSuccess) {
                        Toast.makeText(this@ReportProblemActivity, R.string.dcadm_report_success, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }
}
