package com.gadware.dcadm.data

import com.gadware.dcadm.DcadmConfig
import com.gadware.dcadm.utils.DcadmLog
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReportProblemRepository {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun getReportsCollection() =
        firestore.collection(DcadmConfig.getFirestoreReportsCollectionName())

    /**
     * Checks if the user already has an active report with "Pending" status in Firestore.
     */
    suspend fun checkPendingReport(email: String): Result<ProblemReport?> = withContext(Dispatchers.IO) {
        if (email.isBlank()) {
            return@withContext Result.success(null)
        }

        try {
            val query = getReportsCollection()
                .whereEqualTo("email", email.trim())
                .whereEqualTo("status", "Pending")
                .limit(1)
                .get()
                .await()

            if (!query.isEmpty) {
                val doc = query.documents[0]
                val report = doc.toObject(ProblemReport::class.java)?.copy(id = doc.id)
                Result.success(report)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            DcadmLog.e("ReportProblemRepo", "Failed to check pending reports: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's country name via IP geolocation service.
     */
    suspend fun getCountryName(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://ip-api.com/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (json.optString("status") == "fail") return@withContext null
                val country = json.optString("country")
                if (country.isNotBlank()) country else null
            }
        } catch (e: Exception) {
            DcadmLog.d("ReportProblemRepo", "Country lookup by IP failed: ${e.message}")
            null
        }
    }

    /**
     * Submits a new problem report to Firestore `/Reports`.
     * Validates fields and guards against duplicate pending submissions.
     */
    suspend fun submitReport(report: ProblemReport): Result<String> = withContext(Dispatchers.IO) {
        try {
            val trimmedEmail = report.email.trim()
            if (trimmedEmail.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Email is required"))
            }
            if (report.name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Name is required"))
            }
            if (report.phone.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Phone number is required"))
            }
            if (report.featureName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Feature category is required"))
            }
            if (report.message.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Message is required"))
            }

            // Prevent duplicate submission check
            val existing = getReportsCollection()
                .whereEqualTo("email", trimmedEmail)
                .whereEqualTo("status", "Pending")
                .limit(1)
                .get()
                .await()

            if (!existing.isEmpty) {
                return@withContext Result.failure(IllegalStateException("You already have a pending report"))
            }

            val reportData = hashMapOf(
                "appName" to report.appName.ifBlank { DcadmConfig.getAppName() },
                "country" to report.country.ifBlank { "Unknown" },
                "email" to trimmedEmail,
                "featureName" to report.featureName.trim(),
                "message" to report.message.trim(),
                "name" to report.name.trim(),
                "phone" to report.phone.trim(),
                "status" to "Pending",
                "subject" to report.subject.ifBlank { "Report Problem" },
                "timestamp" to FieldValue.serverTimestamp()
            )

            val docRef = getReportsCollection().add(reportData).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            DcadmLog.e("ReportProblemRepo", "Failed to submit report: ${e.message}")
            Result.failure(e)
        }
    }
}
