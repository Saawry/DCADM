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
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.R
import com.gadware.dcadm.databinding.DcadmActivitySettingsBinding
import com.gadware.dcadm.databinding.DcadmDialogConfigureDriveBinding
import com.gadware.dcadm.databinding.DcadmDialogRestoreBinding
import com.gadware.dcadm.ui.DcadmViewModel
import com.gadware.dcadm.ui.DcadmViewModelFactory
import kotlinx.coroutines.launch
import java.util.Locale

class BackupActivity : AppCompatActivity() {
    private val TAG = "Dcadm_Trace"

    private lateinit var binding: DcadmActivitySettingsBinding
    private lateinit var viewModel: DcadmViewModel

    private lateinit var intentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var restoreLauncher: ActivityResultLauncher<String>
    private var alert: AlertDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        val sharedPref = newBase.getSharedPreferences("dcadm_locale_pref", android.content.Context.MODE_PRIVATE)
        val language = sharedPref.getString("language", "en") ?: "en"
        val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Android 15 (API 35) and above uses the modern Java 19+ factory method
            Locale.of(language)
        } else {
            // Android 14 and below uses the standard BCP 47 language tag parser
           Locale(language)
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

        val dbClass = DcadmConfig.getDatabaseClass() ?: throw IllegalStateException("DcadmConfig not initialized")
        val method = dbClass.getMethod("getDatabase", android.content.Context::class.java)
        val database = method.invoke(null, this) as androidx.room.RoomDatabase

        val factory = DcadmViewModelFactory(this, database)
        viewModel = ViewModelProvider(this, factory)[DcadmViewModel::class.java]

        val userType = intent.getStringExtra("UserType") ?: ""
        if (userType == "NewUser") {
            //get email from session, auth drive,updateDrive mail, save token(from google auth manager),
            showConfigMailDialog()
        }else if (userType == "OldUser") {
            //prompt restore if file exists, if yes, verify and restore//restore may not need verification
            showRestoreDialog()
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

    private fun showConfigMailDialog() {
        Log.d(TAG, "BackupActivity: showConfigMailDialog")
        if (alert != null) return // prevent multiple dialogs

        val dialogBinding = DcadmDialogConfigureDriveBinding.inflate(layoutInflater)

        alert = AlertDialog.Builder(this)
            .setTitle(R.string.dcadm_dialog_configure_drive_title)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        alert?.show()

        dialogBinding.btnConfigure.setOnClickListener {
            //auth drive, no restore
            viewModel.authDriveMail(this@BackupActivity)
            alert?.dismiss()
            alert=null
        }
        dialogBinding.btnCancel.setOnClickListener {
            alert?.dismiss()
            alert=null
        }

        alert?.setOnDismissListener {
            //viewModel.onConfigDismissed()
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
    }

    private fun setupUI() {

        binding.btnBack.setOnClickListener { finish() }

       

        
        binding.btnBackupDrive.setOnClickListener {
            viewModel.onManualBackupClicked(this)
        }

        binding.btnRestoreDrive.setOnClickListener {
            viewModel.onManualRestoreClicked(this)
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

    private fun observeState() {
        Log.d(TAG, "BackupActivity: observeState")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->

                    // AUTH
                    if (uiState.showAuthResolution && uiState.authResolutionIntent != null) {
                        val request = IntentSenderRequest.Builder(
                            uiState.authResolutionIntent.intentSender
                        ).build()
                        intentLauncher.launch(request)
                    }

                    // PROFILE
                    binding.tvLoginEmail.text = uiState.userEmail ?: "Unknown"
                    uiState.userProfile?.let { profile ->
                        binding.tvUserName.text = profile.name
                        val userTypeCapitalized = profile.userType.replaceFirstChar { it.uppercase() }
                        val statusCapitalized = profile.status.replaceFirstChar { it.uppercase() }
                        binding.tvUserType.text = "$userTypeCapitalized Account • $statusCapitalized"
                        binding.tvUserCountry.text = profile.country
                    }

                    // BACKUP STATUS
                    binding.tvLastBackup.text = uiState.lastBackupDateStr

                    // DRIVE LOADING
                    binding.progressDrive.visibility =
                        if (uiState.isLoading) View.VISIBLE else View.GONE

                    binding.tvDriveStatus.text = if (uiState.driveEmail != null) {
                        "Connected: ${uiState.driveEmail}"
                    } else {
                        uiState.statusText
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