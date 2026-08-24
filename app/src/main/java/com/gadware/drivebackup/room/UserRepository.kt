package com.gadware.drivebackup.room

import kotlinx.coroutines.flow.Flow

class UserRepository(private val userDao: UserDao) {

    val allUsersFlow: Flow<List<User>> = userDao.getAllUsersFlow()
    val userCountFlow: Flow<Int> = userDao.getUserCountFlow()

    suspend fun insert(user: User): Long = userDao.insertUser(user)
    suspend fun insertMultiple(users: List<User>): List<Long> = userDao.insertUsers(users)
    suspend fun update(user: User) = userDao.updateUser(user)
    suspend fun delete(user: User) = userDao.deleteUser(user)
    suspend fun deleteAll() = userDao.deleteAllUsers()
    suspend fun getAll(): List<User> = userDao.getAllUsers()
    suspend fun getCount(): Int = userDao.getUserCount()
}

