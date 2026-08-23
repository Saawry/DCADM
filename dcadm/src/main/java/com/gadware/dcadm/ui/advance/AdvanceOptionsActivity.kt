package com.gadware.dcadm.ui.advance

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.databinding.DcadmActivityAdvanceOptionsBinding
import com.gadware.dcadm.ui.DcadmViewModel
import com.gadware.dcadm.ui.DcadmViewModelFactory
import com.gadware.dcadm.utils.DcadmLog
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AdvanceOptionsActivity : AppCompatActivity() {

    private lateinit var binding: DcadmActivityAdvanceOptionsBinding
    private lateinit var viewModel: DcadmViewModel

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

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

        binding = DcadmActivityAdvanceOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dbClass = DcadmConfig.getDatabaseClass()
            ?: throw IllegalStateException("DcadmConfig not initialized with database class")
        val method = dbClass.getMethod("getDatabase", android.content.Context::class.java)
        val database = method.invoke(null, this) as androidx.room.RoomDatabase

        val factory = DcadmViewModelFactory(this, database)
        viewModel = ViewModelProvider(this, factory)[DcadmViewModel::class.java]

        setupLaunchers()
        setupUI()
        observeState()

        viewModel.loadDatabaseStats()
    }

    private fun setupLaunchers() {
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) {
                viewModel.exportLocalDatabase(uri)
            }
        }

        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                showImportConfirmationDialog(uri)
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnExportLocal.setOnClickListener {
            val dbName = DcadmConfig.getDatabaseName()
            val defaultFileName = "${dbName}_backup_${System.currentTimeMillis()}.zip"
            exportLauncher.launch(defaultFileName)
        }

        binding.btnShareBackup.setOnClickListener {
            shareBackupFile()
        }

        binding.btnImportLocal.setOnClickListener {
            importLauncher.launch(arrayOf("*/*", "application/zip", "application/octet-stream", "application/x-sqlite3"))
        }

        binding.btnIntegrityCheck.setOnClickListener {
            viewModel.runIntegrityCheck()
        }

        binding.btnReportProblem.setOnClickListener {
            handleReportProblem()
        }
    }

    private fun showImportConfirmationDialog(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dcadm_import_confirm_title)
            .setMessage(R.string.dcadm_import_confirm_msg)
            .setIcon(R.drawable.outline_warning_amber_24)
            .setPositiveButton(R.string.dcadm_import_confirm_btn) { dialog, _ ->
                dialog.dismiss()
                viewModel.importLocalDatabase(uri)
            }
            .setNegativeButton(R.string.dcadm_btn_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun shareBackupFile() {
        viewModel.createShareableBackup { file: File? ->
            if (file == null || !file.exists()) {
                Toast.makeText(this, R.string.dcadm_advance_share_failed, Toast.LENGTH_SHORT).show()
                return@createShareableBackup
            }

            try {
                val authority = "${packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(this, authority, file)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.dcadm_share_chooser_title)))
            } catch (e: Exception) {
                DcadmLog.e("AdvanceOptions", "Share failed: ${e.message}")
                // Fallback to sending intent with standard URI
                Toast.makeText(this, getString(R.string.dcadm_advance_share_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleReportProblem() {
        val configuredClass = DcadmConfig.getReportProblemActivityClassName()
        if (!configuredClass.isNullOrBlank()) {
            try {
                val intent = Intent()
                intent.setClassName(this, configuredClass)
                startActivity(intent)
                return
            } catch (e: Exception) {
                DcadmLog.d("AdvanceOptions", "Configured report activity failed: ${e.message}")
            }
        }

        // Try standard host report activity convention
        try {
            val intent = Intent()
            intent.setClassName(this, "${packageName}.activities.ReportProblemActivity")
            startActivity(intent)
            return
        } catch (ignored: Exception) {}

        // Fallback to email
        val supportEmail = DcadmConfig.getSupportEmail()
        if (!supportEmail.isNullOrBlank()) {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$supportEmail")
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.dcadm_advance_report_email_subject, DcadmConfig.getAppName()))
            }
            try {
                startActivity(Intent.createChooser(emailIntent, getString(R.string.dcadm_advance_send_report_title)))
                return
            } catch (ignored: Exception) {}
        }

        Toast.makeText(this, R.string.dcadm_advance_report_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    // Loading / Progress
                    val isBusy = uiState.isExportingLocal || uiState.isImportingLocal || uiState.isIntegrityChecking || uiState.isLoading
                    binding.progressBar.visibility = if (isBusy) View.VISIBLE else View.GONE

                    // Stats
                    uiState.databaseStats?.let { stats ->
                        binding.tvDbName.text = stats.dbName
                        binding.tvDbSize.text = stats.formattedSize
                    }

                    // Integrity status
                    if (!uiState.integrityCheckResult.isNullOrBlank()) {
                        binding.tvIntegrityStatus.text = uiState.integrityCheckResult
                        binding.tvIntegrityStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvIntegrityStatus.visibility = View.GONE
                    }

                    // Operation Banner Message
                    if (!uiState.localOperationMessage.isNullOrBlank()) {
                        binding.tvOperationStatus.text = uiState.localOperationMessage
                        binding.tvOperationStatus.visibility = View.VISIBLE
                    } else if (!uiState.error.isNullOrBlank()) {
                        binding.tvOperationStatus.text = uiState.error
                        binding.tvOperationStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvOperationStatus.visibility = View.GONE
                    }
                }
            }
        }
    }
}
