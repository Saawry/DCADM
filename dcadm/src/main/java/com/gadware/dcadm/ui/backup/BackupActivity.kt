package com.gadware.dcadm.ui.backup

import android.util.Log
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.databinding.DcadmActivitySettingsBinding
import com.gadware.dcadm.databinding.DcadmDialogConfigureDriveBinding
import com.gadware.dcadm.databinding.DcadmDialogRestoreBinding
import com.gadware.dcadm.ui.DcadmViewModel
import com.gadware.dcadm.ui.DcadmViewModelFactory
import com.gadware.dcadm.utils.DcadmLog
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class BackupActivity : AppCompatActivity() {
    private val TAG = "Dcadm_Trace"

    private lateinit var binding: DcadmActivitySettingsBinding
    private lateinit var viewModel: DcadmViewModel

    private lateinit var intentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var restoreLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private var alert: AlertDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        val sharedPref = newBase.getSharedPreferences("dcadm_locale_pref", android.content.Context.MODE_PRIVATE)
        val language = sharedPref.getString("language", "en") ?: "en"
        val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Android 15 (API 35) and above uses the modern Java 19+ factory method
            Locale.of(language)
        } else {
            // Android 14 and below uses the standard BCP 47 language tag parser
            Locale.forLanguageTag(language)
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "BackupActivity: onCreate")
        super.onCreate(savedInstanceState)

        binding = DcadmActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = DcadmConfig.getDatabase(this)
        val factory = DcadmViewModelFactory(this, database)
        viewModel = ViewModelProvider(this, factory)[DcadmViewModel::class.java]

        val userType = intent.getStringExtra("UserType") ?: ""
        if (userType == "OldUser") {
            lifecycleScope.launch {
                val isEmpty = viewModel.checkIfDatabaseEmpty()
                if (isEmpty) {
                    showRestoreDialog()
                }
            }
        }

        setupLaunchers()
        setupUI()
        observeState()
    }

    override fun onStart() {
        Log.d(TAG, "BackupActivity: onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.d(TAG, "BackupActivity: onResume")
        super.onResume()
        viewModel.loadSettings()
    }

    override fun onPause() {
        Log.d(TAG, "BackupActivity: onPause")
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "BackupActivity: onStop")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "BackupActivity: onDestroy")
        super.onDestroy()
    }

    private fun showRestoreDialog() {
        Log.d(TAG, "BackupActivity: showRestoreDialog")
        if (alert != null) return // prevent multiple dialogs

        val dialogBinding = DcadmDialogRestoreBinding.inflate(layoutInflater)

        alert = AlertDialog.Builder(this)
            .setTitle(R.string.dcadm_dialog_restore_title)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        alert?.show()
        dialogBinding.btnRestore.setOnClickListener {
            //authorize drive using GoogleAuthManager's requestDriveAccess method, then restore using viewModel.onManualRestoreClicked method
            viewModel.onManualRestoreClicked(this)
            alert?.dismiss()
            alert=null
        }
        dialogBinding.btnCancel.setOnClickListener {
            alert?.dismiss()
            alert=null
        }

        alert?.setOnDismissListener {
            //viewModel.onRestoreDismissed()
            alert?.dismiss()
            alert = null
        }
    }

    private fun setupLaunchers() {
        intentLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                viewModel.onAuthResolutionResult(
                    it.resultCode == RESULT_OK,
                    this
                )
            }

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

        binding.cardUserProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnEditProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnBackupDrive.setOnClickListener {
            viewModel.onManualBackupClicked(this)
        }

        binding.btnRestoreDrive.setOnClickListener {
            viewModel.onManualRestoreClicked(this)
        }

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

        binding.btnSaveRoutine.setOnClickListener {
            viewModel.saveRoutineConfig()
        }

        val options = listOf("Never", "Daily", "Weekly", "Monthly")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spinnerRoutine.adapter = adapter

        binding.spinnerRoutine.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    viewModel.onRoutineConfigChanged(options[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
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
                android.widget.Toast.makeText(this, R.string.dcadm_advance_share_failed, android.widget.Toast.LENGTH_SHORT).show()
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
                DcadmLog.e(TAG, "Share failed: ${e.message}")
                android.widget.Toast.makeText(this, getString(R.string.dcadm_advance_share_error, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        Log.d(TAG, "BackupActivity: observeState")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->

                    // AUTH NAVIGATION
                    if (uiState.shouldNavigateToLogin) {
                        val loginIntent = android.content.Intent(this@BackupActivity, com.gadware.dcadm.ui.login.LoginActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(loginIntent)
                        finish()
                        return@collect
                    }

                    // AUTH RESOLUTION PROMPT
                    if (uiState.showAuthResolution && uiState.authResolutionIntent != null) {
                        val request = IntentSenderRequest.Builder(
                            uiState.authResolutionIntent.intentSender
                        ).build()
                        intentLauncher.launch(request)
                    }

                    // PROFILE
                    binding.tvLoginEmail.text = uiState.userEmail ?: getString(R.string.dcadm_user_unknown)
                    uiState.userProfile?.let { profile ->
                        binding.tvUserName.text = profile.name
                        com.gadware.dcadm.utils.ImageLoader.load(
                            binding.ivUserAvatar,
                            profile.photoUrl,
                            R.drawable.outline_person_24
                        )
                        val userTypeCapitalized = profile.userType.replaceFirstChar { it.uppercase() }
                        val statusCapitalized = profile.status.replaceFirstChar { it.uppercase() }
                        binding.tvUserType.text = getString(
                            R.string.dcadm_user_account_status_format,
                            userTypeCapitalized,
                            statusCapitalized
                        )
                        binding.tvUserCountry.text = profile.country

                        // Individual Local Database Export & Import visible if export is true
                        val isExportAllowed = profile.export
                        binding.cardExportDatabase.visibility = if (isExportAllowed) View.VISIBLE else View.GONE
                        binding.cardImportDatabase.visibility = if (isExportAllowed) View.VISIBLE else View.GONE
                    } ?: run {
                        binding.cardExportDatabase.visibility = View.GONE
                        binding.cardImportDatabase.visibility = View.GONE
                    }

                    // LOCAL DB OPERATIONS PROGRESS & BUTTON STATES
                    val isLocalBusy = uiState.isExportingLocal || uiState.isImportingLocal
                    binding.progressExport.visibility = if (uiState.isExportingLocal) View.VISIBLE else View.GONE
                    binding.progressImport.visibility = if (uiState.isImportingLocal) View.VISIBLE else View.GONE
                    binding.btnExportLocal.isEnabled = !isLocalBusy
                    binding.btnShareBackup.isEnabled = !isLocalBusy
                    binding.btnImportLocal.isEnabled = !isLocalBusy

                    // BACKUP STATUS
                    binding.tvLastBackup.text = uiState.lastBackupDateStr

                    // DRIVE LOADING
                    binding.progressDrive.visibility =
                        if (uiState.isLoading) View.VISIBLE else View.GONE

                    binding.tvDriveStatus.text = if (uiState.driveEmail != null) {
                        getString(R.string.dcadm_settings_drive_connected, uiState.driveEmail)
                    } else {
                        uiState.statusText
                    }

                    // TOAST NOTIFICATIONS
                    if (!uiState.toastMessage.isNullOrBlank()) {
                        android.widget.Toast.makeText(this@BackupActivity, uiState.toastMessage, android.widget.Toast.LENGTH_LONG).show()
                        viewModel.onToastShown()
                    }

                    // ERROR
                    if (uiState.error != null) {
                        binding.tvError.text = uiState.error
                        binding.tvError.visibility = View.VISIBLE
                    } else {
                        binding.tvError.visibility = View.GONE
                    }
                }
            }
        }
    }

}