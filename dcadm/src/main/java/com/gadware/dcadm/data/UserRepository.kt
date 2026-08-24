package com.gadware.dcadm.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserRepository {
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    fun isSignedIn(): Boolean {
        return auth.currentUser != null
    }

    fun getUserId(): String? {
        return auth.currentUser?.uid
    }

    fun getUserEmail(): String? {
        return auth.currentUser?.email
    }

    suspend fun getUserProfile(
        uid: String = auth.currentUser?.uid ?: "",
        context: Context,
        forceRefresh: Boolean = false
    ): UserProfile? {
        if (uid.isBlank()) return null

        val sessionManager = SessionManager(context)
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        if (!forceRefresh && sessionManager.getLastProfileSyncDate() == currentDate) {
            val cachedProfile = sessionManager.getUserProfile()
            if (cachedProfile != null) return cachedProfile
        }

        return try {
            var snapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).get().await()
            
            if (!snapshot.exists()) {
                // Try fallback by email as document ID
                val email = auth.currentUser?.email ?: getUserEmail()
                val uid = auth.currentUser?.uid
                if (!uid.isNullOrBlank()) {
                    snapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).get().await()
                }
            }

            if (!snapshot.exists()) {
                // Try fallback by email query
                val email = auth.currentUser?.email ?: getUserEmail()
                if (!email.isNullOrBlank()) {
                    val emailSnapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName())
                        .whereEqualTo("email", email)
                        .get().await()
                    if (!emailSnapshot.isEmpty) {
                        snapshot = emailSnapshot.documents[0]
                    }
                }
            }

            if (snapshot.exists()) {
                val profile = snapshot.toObject(UserProfile::class.java)
                val finalProfile = if (profile != null) {
                    val resolvedPhotoUrl = if (profile.photoUrl.isBlank()) {
                        auth.currentUser?.photoUrl?.toString() ?: ""
                    } else {
                        profile.photoUrl
                    }
                    val updated = profile.copy(photoUrl = resolvedPhotoUrl)
                    sessionManager.saveUserProfile(updated)
                    sessionManager.saveLastProfileSyncDate(currentDate)
                    updated
                } else {
                    null
                }
                finalProfile
            } else {
                null
            }
        } catch (e: Exception) {
            // Fallback to cache on error
            sessionManager.getUserProfile()
        }
    }

    suspend fun registerUser(userProfile: UserProfile, context: Context): Result<Unit> {
        val uid = userProfile.userId.takeIf { it.isNotBlank() && it != "pending" }
            ?: auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Cannot register user without valid Firebase UID"))

        val photoUrl = if (userProfile.photoUrl.isBlank()) {
            auth.currentUser?.photoUrl?.toString() ?: ""
        } else {
            userProfile.photoUrl
        }

        val finalProfile = userProfile.copy(
            userId = uid,
            photoUrl = photoUrl
        )

        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).set(finalProfile).await()
            val sessionManager = SessionManager(context)
            sessionManager.saveUserProfile(finalProfile)
            sessionManager.saveLastProfileSyncDate(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isUserOld(uid: String): Boolean {
        if (uid.isBlank()) return false
        return try {
            val snapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).get().await()
            snapshot.exists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun isUserRegistered(uid: String): Boolean {
        if (uid.isBlank()) return false
        return try {
            // 1. Try UID as document ID
            var snapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).get().await()

            // 2. Try Email as document ID if UID failed
            if (!snapshot.exists()) {
                val email = auth.currentUser?.email ?: getUserEmail()
                if (!email.isNullOrBlank()) {
                    snapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(email).get().await()
                }
            }

            // 3. Try query by email if document lookups failed
            if (!snapshot.exists()) {
                val email = auth.currentUser?.email ?: getUserEmail()
                if (!email.isNullOrBlank()) {
                    val emailSnapshot = firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName())
                        .whereEqualTo("email", email)
                        .get().await()

                    if (!emailSnapshot.isEmpty) {
                        snapshot = emailSnapshot.documents[0]
                    }
                }
            }

            if (snapshot.exists()) {
                val regStatus = snapshot.getString("regStatus")
                return regStatus == "registered"
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateDriveEmail(
        uid: String = auth.currentUser?.uid ?: "",
        driveEmail: String,
        context: Context
    ): Result<Unit> {
        if (uid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank"))
        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid)
                .update("driveEmail", driveEmail).await()
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(driveEmail = driveEmail))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLastBackupDate(
        uid: String = auth.currentUser?.uid ?: "",
        lastBackupDate: String,
        context: Context
    ): Result<Unit> {
        val targetUid = uid.ifBlank { auth.currentUser?.uid ?: "" }
        if (targetUid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank"))
        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(targetUid)
                .update("lastBackupDate", lastBackupDate).await()
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(lastBackupDate = lastBackupDate))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(lastBackupDate = lastBackupDate))
            }
            Result.failure(e)
        }
    }

    suspend fun updateExportStatus(
        uid: String = auth.currentUser?.uid ?: "",
        export: Boolean,
        context: Context
    ): Result<Unit> {
        val targetUid = uid.ifBlank { auth.currentUser?.uid ?: "" }
        if (targetUid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank"))
        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(targetUid)
                .update("export", export).await()
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(export = export))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDeviceToken(
        uid: String = auth.currentUser?.uid ?: "",
        deviceToken: String,
        context: Context
    ): Result<Unit> {
        val targetUid = uid.ifBlank { auth.currentUser?.uid ?: "" }
        if (targetUid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank"))
        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(targetUid)
                .update("deviceToken", deviceToken).await()
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(deviceToken = deviceToken))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val sessionManager = SessionManager(context)
            val profile = sessionManager.getUserProfile()
            if (profile != null) {
                sessionManager.saveUserProfile(profile.copy(deviceToken = deviceToken))
            }
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(userProfile: UserProfile, context: Context): Result<Unit> {
        val uid = userProfile.userId.takeIf { it.isNotBlank() && it != "pending" }
            ?: auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Cannot update profile without valid Firebase UID"))

        return try {
            val updates = mapOf(
                "name" to userProfile.name,
                "phoneNumber" to userProfile.phoneNumber,
                "address" to userProfile.address,
                "country" to userProfile.country,
                "photoUrl" to userProfile.photoUrl
            )
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid)
                .update(updates).await()

            val sessionManager = SessionManager(context)
            sessionManager.saveUserProfile(userProfile)
            com.gadware.dcadm.notification.DcadmNotificationManager.syncTopics(context, userProfile)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUserProfile(uid: String = auth.currentUser?.uid ?: ""): Result<Unit> {
        if (uid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank"))
        return try {
            firestore.collection(com.gadware.dcadm.DcadmConfig.getFirestoreUserCollectionName()).document(uid).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
