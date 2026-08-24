package com.gadware.drivebackup

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.auth.GoogleAuthManager
import com.gadware.dcadm.data.SessionManager
import com.gadware.dcadm.data.UserRepository
import com.gadware.dcadm.ui.backup.BackupActivity
import com.gadware.dcadm.ui.login.LoginActivity
import com.gadware.dcadm.ui.registration.RegistrationActivity
import com.gadware.drivebackup.databinding.ActivityMainBinding
import com.gadware.drivebackup.room.UserViewModel
import com.gadware.drivebackup.room.UserViewModelFactory
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var userViewModel: UserViewModel
    private val dcadmUserRepo = UserRepository()
    private lateinit var sessionManager: SessionManager
    private lateinit var authManager: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        authManager = GoogleAuthManager(this)

        val factory = UserViewModelFactory(this)
        userViewModel = ViewModelProvider(this, factory)[UserViewModel::class.java]

        setupListeners()
        observeDatabaseCount()
    }

    override fun onResume() {
        super.onResume()
        checkAuthAndSyncProfile()
    }

    private fun setupListeners() {
        // Profile Management
        binding.cardUserProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnEditProfile.setOnClickListener {
            DcadmConfig.openProfile(this)
        }

        binding.btnGoogleSignin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        binding.btnSignOut.setOnClickListener {
            lifecycleScope.launch {
                authManager.signOut()
                sessionManager.clearSession()
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }

        // Database Management & Sample Operations
        binding.btnInsertData.setOnClickListener {
            val intent = Intent(this, InsertDataActivity::class.java)
            startActivity(intent)
        }

        binding.btnAddSampleData.setOnClickListener {
            userViewModel.insertSampleUsers(3) {
                android.widget.Toast.makeText(this, "Added 3 sample users to DB", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearDb.setOnClickListener {
            userViewModel.deleteAll {
                android.widget.Toast.makeText(this, "Cleared all local database records (Empty DB)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Cloud Backup & Settings
        binding.btnBackup.setOnClickListener {
            val intent = Intent(this, BackupActivity::class.java)
            startActivity(intent)
        }

        // Push Notification & Topics Testing
        binding.btnTestNotification.setOnClickListener {
            com.gadware.dcadm.notification.DcadmNotificationManager.showNotification(
                this,
                title = "DCADM Push Test",
                message = "Push notification channel & dispatch are functioning correctly!"
            )
            android.widget.Toast.makeText(this, "Dispatched local test notification", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnSyncPushTopics.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            com.gadware.dcadm.notification.DcadmNotificationManager.syncDeviceToken(this) { token ->
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.GONE
                    val msg = if (!token.isNullOrBlank()) "FCM Token & Topics Synced" else "Failed to sync token"
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Advance Options
        binding.cardAdvanceOptionsMain.setOnClickListener {
            DcadmConfig.openAdvanceOptions(this)
        }
    }

    private fun checkAuthAndSyncProfile() {
        // 1. Verify Authentication
        if (!dcadmUserRepo.isSignedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
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
                val intent = Intent(this@MainActivity, RegistrationActivity::class.java)
                intent.putExtra("email", email)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                return@launch
            }

            // 3. Load Profile Data & Update Dashboard
            val profile = dcadmUserRepo.getUserProfile(uid, this@MainActivity, forceRefresh = true)
            binding.progressBar.visibility = View.GONE

            if (profile != null) {
                binding.tvUserName.text = profile.name.ifBlank { "User" }
                binding.tvUserEmail.text = profile.email.ifBlank { email }
                binding.tvUserCountry.text = if (profile.country.isNotBlank()) "Country: ${profile.country}" else "Country: Not set"

                com.gadware.dcadm.utils.ImageLoader.load(
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
                val countryCode = com.gadware.dcadm.data.CountryData.findByNameOrCode(profile.country)?.code
                    ?: profile.country.take(2).ifBlank { Locale.getDefault().country }
                val countryTopic = "country_${countryCode.lowercase(Locale.US)}"
                val tierTopic = if (isPaid) "paid_users" else "free_users"
                binding.tvNotificationTopics.text = "Topics: all, updates, $tierTopic, $countryTopic"

                // Advance Options are visible if export is true
                binding.cardAdvanceOptionsMain.visibility = if (profile.export) View.VISIBLE else View.GONE
            } else {
                binding.tvUserName.text = "Active User"
                binding.tvUserEmail.text = email
                binding.tvUserCountry.text = "Country: Not set"
                binding.chipUserType.text = "FREE"
                binding.tvNotificationTopics.text = "Topics: all, updates, free_users"
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
                }
            }
        }
    }
}
