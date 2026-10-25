package com.gadware.drivebackup

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.BackupRepository
import com.gadware.dcadm.data.CountryData
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserProfile
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.notification.DcadmNotificationManager
import com.gadware.dcadm.update.DcadmUpdateMode
import com.gadware.dcadm.utils.ImageLoader
import com.gadware.drivebackup.databinding.ActivityMainBinding
import com.gadware.drivebackup.room.AppDatabase
import com.gadware.drivebackup.room.UserViewModel
import com.gadware.drivebackup.room.UserViewModelFactory
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var userViewModel: UserViewModel
    private val dcadmUserRepo = UserRepository()
    private lateinit var sessionManager: SessionManager
    private lateinit var authManager: GoogleAuthManager
    private lateinit var backupRepo: BackupRepository

    private var currentProfile: UserProfile? = null

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var exportDbLauncher: ActivityResultLauncher<String>
    private lateinit var importDbLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        authManager = GoogleAuthManager(this)
        backupRepo = BackupRepository(this, AppDatabase.getDatabase(this), authManager)

        val factory = UserViewModelFactory(this)
        userViewModel = ViewModelProvider(this, factory)[UserViewModel::class.java]

        setupLaunchers()
        setupListeners()
        observeDatabaseCount()
        updateNotificationPermissionStatus()

        // Automatically check for in-app updates in background on launch
        DcadmConfig.checkForUpdates(this, mode = DcadmUpdateMode.FLEXIBLE)
    }

    override fun onResume() {
        super.onResume()
        checkAuthAndSyncProfile()
        updateDatabaseStats()
        updateNotificationPermissionStatus()
    }

    private fun setupLaunchers() {
        // Notification Permission Launcher (Android 13+ / API 33+)
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            updateNotificationPermissionStatus()
            if (isGranted) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
                dispatchTestNotification()
            } else {
                Toast.makeText(this, "Notification permission was denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Direct SAF Database Export Launcher (.zip)
        exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            if (uri != null) {
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    val result = backupRepo.exportDatabaseToUri(uri)
                    binding.progressBar.visibility = View.GONE
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, "Database exported successfully to file", Toast.LENGTH_LONG).show()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown export error"
                        Toast.makeText(this@MainActivity, "Export failed: $error", Toast.LENGTH_LONG).show()
                    }
                    updateDatabaseStats()
                }
            }
        }

        // Direct SAF Database Import Launcher (.zip / .db)
        importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                showImportConfirmDialog(uri)
            }
        }
    }

    private fun setupListeners() {
        // Profile & Account Management
        binding.cardUserProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnEditProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnGoogleSignin.setOnClickListener {
            DcadmConfig.openLogin(this)
        }

        binding.btnSignOut.setOnClickListener {
            lifecycleScope.launch {
                authManager.signOut()
                sessionManager.clearSession()
                DcadmConfig.openLogin(this@MainActivity)
                finish()
            }
        }

        // Export Permission Toggle (Developer / Tester Tool)
        binding.btnToggleExportPermission.setOnClickListener {
            val uid = dcadmUserRepo.getUserId() ?: return@setOnClickListener
            val currentExport = currentProfile?.export ?: false
            val newExport = !currentExport

            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = dcadmUserRepo.updateExportStatus(uid, newExport, this@MainActivity)
                binding.progressBar.visibility = View.GONE
                if (result.isSuccess) {
                    val statusText = if (newExport) "GRANTED" else "REVOKED"
                    Toast.makeText(this@MainActivity, "Export permission $statusText for testing", Toast.LENGTH_SHORT).show()
                    checkAuthAndSyncProfile()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Failed to update export permission"
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Database Management & Sample Operations
        binding.btnInsertData.setOnClickListener {
            val intent = Intent(this, InsertDataActivity::class.java)
            startActivity(intent)
        }

        binding.btnAddSampleData.setOnClickListener {
            userViewModel.insertSampleUsers(3) {
                DcadmConfig.recordReviewMilestoneAction(this)
                DcadmConfig.logCrashlyticsBreadcrumb("Added 3 sample users from MainActivity")
                Toast.makeText(this, "Added 3 sample users to DB", Toast.LENGTH_SHORT).show()
                updateDatabaseStats()
            }
        }

        binding.btnClearDb.setOnClickListener {
            userViewModel.deleteAll {
                DcadmConfig.logCrashlyticsBreadcrumb("Cleared all DB records from MainActivity")
                Toast.makeText(this, "Cleared all local database records (Empty DB)", Toast.LENGTH_SHORT).show()
                updateDatabaseStats()
            }
        }

        // Direct Local Database Operations (SAF Export, Import, Share & Integrity Check)
        binding.btnExportDbDirect.setOnClickListener {
            val dbName = DcadmConfig.getDatabaseName()
            val defaultFileName = "${dbName}_backup_${System.currentTimeMillis()}.zip"
            exportDbLauncher.launch(defaultFileName)
        }

        binding.btnImportDbDirect.setOnClickListener {
            importDbLauncher.launch(arrayOf("*/*", "application/zip", "application/octet-stream", "application/x-sqlite3"))
        }

        binding.btnShareDbDirect.setOnClickListener {
            shareBackupArchive()
        }

        binding.btnCheckIntegrity.setOnClickListener {
            performIntegrityCheck()
        }

        // Google Drive Cloud Backup & Settings
        binding.btnBackup.setOnClickListener {
            DcadmConfig.openBackup(this)
        }

        binding.btnTestRoutineWorkerNow.setOnClickListener {
            Toast.makeText(this, "Triggering Routine BackupWorker via WorkManager...", Toast.LENGTH_SHORT).show()
            val workId = DcadmConfig.triggerRoutineBackupNow(this)
            WorkManager.getInstance(this)
                .getWorkInfoByIdLiveData(workId)
                .observe(this) { workInfo: WorkInfo? ->
                    if (workInfo != null) {
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                binding.progressBar.visibility = View.VISIBLE
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                binding.progressBar.visibility = View.GONE
                                Toast.makeText(this, "Routine Backup Worker: SUCCEEDED!", Toast.LENGTH_LONG).show()
                                val lastBackupTimestamp = sessionManager.getLastBackupDate()
                                if (lastBackupTimestamp > 0) {
                                    val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(lastBackupTimestamp))
                                    binding.tvLastBackupStatus.text = "Last Cloud Backup: $dateStr"
                                }
                            }
                            WorkInfo.State.FAILED -> {
                                binding.progressBar.visibility = View.GONE
                                val error = workInfo.outputData.getString("error") ?: "Failed"
                                Toast.makeText(this, "Routine Backup Worker FAILED: $error", Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                }
        }

        // Push Notifications & Topics Testing
        binding.btnTestNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DcadmNotificationManager.hasNotificationPermission(this)
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                dispatchTestNotification()
            }
        }

        binding.btnSyncPushTopics.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            DcadmNotificationManager.syncDeviceToken(this) { token ->
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.GONE
                    val msg = if (!token.isNullOrBlank()) "FCM Token & Topics Synced" else "Failed to sync token"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Play Services, Diagnostics & Review/Update Testing
        binding.btnTestCrashlytics.setOnClickListener {
            DcadmConfig.logCrashlyticsBreadcrumb("Manual test error triggered from MainActivity")
            DcadmConfig.recordCrashlyticsException(
                RuntimeException("DCADM Diagnostic Test: Firebase Crashlytics is active and working properly")
            )
            Toast.makeText(this, "Recorded test breadcrumb & non-fatal exception to Crashlytics", Toast.LENGTH_SHORT).show()
        }

        // In-App Review Testing
        binding.btnTestInAppReview.setOnClickListener {
            DcadmConfig.setInAppReviewTestMode(true)
            DcadmConfig.launchInAppReview(this) { isSuccessful ->
                val msg = if (isSuccessful) "In-App Review completed successfully" else "In-App Review flow unavailable"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestSmartReview.setOnClickListener {
            DcadmConfig.setInAppReviewTestMode(true)
            val triggered = DcadmConfig.checkAndLaunchInAppReview(
                activity = this,
                minDaysSinceInstall = 0,
                minDaysBetweenPrompts = 0,
                minActions = 1
            ) { isSuccessful ->
                Toast.makeText(this, "Smart review complete: $isSuccessful", Toast.LENGTH_SHORT).show()
            }
            if (!triggered) {
                Toast.makeText(this, "Smart review throttled: criteria not met yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenPlayStore.setOnClickListener {
            DcadmConfig.openPlayStore(this)
        }

        // In-App Update Testing
        binding.btnTestInAppUpdate.setOnClickListener {
            DcadmConfig.setInAppUpdateTestMode(enabled = true, isUpdateAvailable = true, isImmediate = false)
            DcadmConfig.checkForUpdates(
                activity = this,
                mode = DcadmUpdateMode.FLEXIBLE
            )
        }

        binding.btnTestImmediateUpdate.setOnClickListener {
            DcadmConfig.setInAppUpdateTestMode(enabled = true, isUpdateAvailable = true, isImmediate = true)
            DcadmConfig.checkForUpdates(
                activity = this,
                mode = DcadmUpdateMode.IMMEDIATE
            )
        }

        // Advance Options (visible when export is allowed)
        binding.cardAdvanceOptionsMain.setOnClickListener {
            DcadmConfig.openAdvanceOptions(this)
        }

        // Report Problem
        binding.cardReportProblemMain.setOnClickListener {
            DcadmConfig.openReportProblem(this)
        }
    }

    private fun dispatchTestNotification() {
        DcadmNotificationManager.showNotification(
            this,
            title = "DCADM Push Test",
            message = "Push notification channel, runtime permission & dispatch are functioning correctly!"
        )
        Toast.makeText(this, "Dispatched test notification", Toast.LENGTH_SHORT).show()
    }

    private fun updateNotificationPermissionStatus() {
        val hasPermission = DcadmNotificationManager.hasNotificationPermission(this)
        binding.tvNotificationPermissionStatus.text = if (hasPermission) {
            "Notification Permission: Granted"
        } else {
            "Notification Permission: Not Granted (Tap Test to request)"
        }
    }

    private fun updateDatabaseStats() {
        try {
            val stats = backupRepo.getDatabaseStats()
            binding.tvDbStats.text = "Size: ${stats.formattedSize} | WAL: ${if (stats.walFileExists) "Active" else "Clean"}\nPath: ${stats.dbPath}"
        } catch (e: Exception) {
            binding.tvDbStats.text = "Database statistics unavailable"
        }
    }

    private fun performIntegrityCheck() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = backupRepo.performIntegrityCheck()
            binding.progressBar.visibility = View.GONE
            val message = if (result.isSuccess) {
                "SQLite PRAGMA integrity_check result: ${result.getOrNull()}\nDatabase WAL checkpoint executed successfully."
            } else {
                "Integrity check failed: ${result.exceptionOrNull()?.message}"
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Database Diagnostics")
                .setMessage(message)
                .setIcon(com.gadware.dcadm.R.drawable.outline_storage_24)
                .setPositiveButton("OK", null)
                .show()

            updateDatabaseStats()
        }
    }

    private fun showImportConfirmDialog(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(com.gadware.dcadm.R.string.dcadm_import_confirm_title)
            .setMessage(com.gadware.dcadm.R.string.dcadm_import_confirm_msg)
            .setIcon(com.gadware.dcadm.R.drawable.outline_warning_amber_24)
            .setPositiveButton(com.gadware.dcadm.R.string.dcadm_import_confirm_btn) { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    val result = backupRepo.importDatabaseFromUri(uri)
                    binding.progressBar.visibility = View.GONE
                    if (result.isSuccess && result.getOrNull() == true) {
                        Toast.makeText(this@MainActivity, "Database restored successfully", Toast.LENGTH_LONG).show()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown import error"
                        Toast.makeText(this@MainActivity, "Import failed: $error", Toast.LENGTH_LONG).show()
                    }
                    updateDatabaseStats()
                }
            }
            .setNegativeButton(com.gadware.dcadm.R.string.dcadm_btn_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun shareBackupArchive() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = backupRepo.createLocalBackupZipFile()
            binding.progressBar.visibility = View.GONE
            if (result.isSuccess) {
                val file: File? = result.getOrNull()
                if (file != null && file.exists()) {
                    try {
                        val authority = "${packageName}.fileprovider"
                        val contentUri = FileProvider.getUriForFile(this@MainActivity, authority, file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, contentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Database Backup Archive"))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Backup file not found", Toast.LENGTH_SHORT).show()
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Failed to generate backup zip"
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAuthAndSyncProfile() {
        // 1. Verify Authentication
        if (!dcadmUserRepo.isSignedIn()) {
            DcadmConfig.openLogin(this)
            finish()
            return
        }

        val uid = dcadmUserRepo.getUserId() ?: ""
        val email = dcadmUserRepo.getUserEmail() ?: sessionManager.getUserEmail() ?: ""

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // 2. Verify Registration in Firestore
            val isRegistered = dcadmUserRepo.isUserRegistered(uid)
            if (!isRegistered) {
                binding.progressBar.visibility = View.GONE
                DcadmConfig.openRegistration(this@MainActivity, email)
                finish()
                return@launch
            }

            // 3. Load Profile Data & Update Dashboard
            val profile = dcadmUserRepo.getUserProfile(uid, this@MainActivity, forceRefresh = true)
            currentProfile = profile
            binding.progressBar.visibility = View.GONE

            if (profile != null) {
                binding.tvUserName.text = profile.name.ifBlank { "User" }
                binding.tvUserEmail.text = profile.email.ifBlank { email }
                binding.tvUserCountry.text = if (profile.country.isNotBlank()) "Country: ${profile.country}" else "Country: Not set"

                ImageLoader.load(
                    binding.ivUserAvatar,
                    profile.photoUrl,
                    com.gadware.dcadm.R.drawable.outline_person_24
                )

                val isPaid = profile.userType.equals("paid", ignoreCase = true)
                binding.chipUserType.text = if (isPaid) "PRO / PAID" else "FREE"

                val (bgColor, textColor) = if (isPaid) {
                    MaterialColors.getColor(binding.chipUserType, com.google.android.material.R.attr.colorTertiaryContainer) to
                            MaterialColors.getColor(binding.chipUserType, com.google.android.material.R.attr.colorOnTertiaryContainer)
                } else {
                    MaterialColors.getColor(binding.chipUserType, com.google.android.material.R.attr.colorPrimaryContainer) to
                            MaterialColors.getColor(binding.chipUserType, com.google.android.material.R.attr.colorOnPrimaryContainer)
                }

                binding.chipUserType.chipBackgroundColor = ColorStateList.valueOf(bgColor)
                binding.chipUserType.setTextColor(textColor)

                // Subscribed Topics info
                val countryCode = CountryData.findByNameOrCode(profile.country)?.code
                    ?: profile.country.take(2).ifBlank { Locale.getDefault().country }
                val countryTopic = "country_${countryCode.lowercase(Locale.US)}"
                val tierTopic = if (isPaid) "paid_users" else "free_users"
                binding.tvNotificationTopics.text = "Topics: all, updates, $tierTopic, $countryTopic"

                // Export Permission Status & Advance Options visibility
                binding.tvExportPermissionStatus.text = if (profile.export) {
                    "Export Permission: Enabled (Advance Tools Unlocked)"
                } else {
                    "Export Permission: Disabled (Advance Tools Hidden)"
                }
                binding.cardAdvanceOptionsMain.visibility = if (profile.export) View.VISIBLE else View.GONE
            } else {
                binding.tvUserName.text = "Active User"
                binding.tvUserEmail.text = email
                binding.tvUserCountry.text = "Country: Not set"
                binding.chipUserType.text = "FREE"
                binding.tvNotificationTopics.text = "Topics: all, updates, free_users"
                binding.tvExportPermissionStatus.text = "Export Permission: Disabled"
                binding.cardAdvanceOptionsMain.visibility = View.GONE
            }

            // Update Last Backup Timestamp
            val lastBackupTimestamp = sessionManager.getLastBackupDate()
            binding.tvLastBackupStatus.text = if (lastBackupTimestamp > 0) {
                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(lastBackupTimestamp))
                "Last Cloud Backup: $dateStr"
            } else {
                "Last Cloud Backup: Never"
            }
        }
    }

    private fun observeDatabaseCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userViewModel.userCountState.collect { count ->
                    binding.tvDbRecordCount.text = "$count ${if (count == 1) "record" else "records"}"
                    updateDatabaseStats()
                }
            }
        }
    }
}
