package com.gadware.drivebackup.room

class UserRepository(private val userDao: UserDao) {

    suspend fun insert(user: User) = userDao.insertUser(user)
    suspend fun update(user: User) = userDao.updateUser(user)
    suspend fun delete(user: User) = userDao.deleteUser(user)
    suspend fun getAll() = userDao.getAllUsers()
}
