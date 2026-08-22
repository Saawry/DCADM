package com.gadware.dcadm.data

data class UserProfile(
    val email: String = "",
    val name: String = "",
    val shopName: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val country: String = "",
    val driveEmail: String = "",
    val regDate: Long = 0L,
    val regStatus: String = "registered",
    val userId: String = "pending",
    val validTill: Long = 0L,
    val deviceToken: String? = null,
    val lastActiveDate: Long = 0L
)
