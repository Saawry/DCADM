package com.gadware.dcadm.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Data model representing a problem report submitted by a user.
 * Mapped to Cloud Firestore collection `/Reports`.
 */
data class ProblemReport(
    @DocumentId
    val id: String? = null,
    val appName: String = "",
    val country: String = "Unknown",
    val email: String = "",
    val featureName: String = "",
    val message: String = "",
    val name: String = "",
    val phone: String = "",
    val status: String = "Pending",
    val subject: String = "Report Problem",
    @ServerTimestamp
    val timestamp: Date? = null
)
