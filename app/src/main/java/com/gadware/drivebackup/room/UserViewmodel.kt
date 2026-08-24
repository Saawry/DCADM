package com.gadware.drivebackup.room

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserViewModel(private val repository: UserRepository) : ViewModel() {

    val usersState: StateFlow<List<User>> = repository.allUsersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userCountState: StateFlow<Int> = repository.userCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun insert(user: User, onComplete: ((Long) -> Unit)? = null) = viewModelScope.launch {
        val id = repository.insert(user)
        onComplete?.invoke(id)
    }

    fun insertSampleUsers(count: Int = 3, onComplete: (() -> Unit)? = null) = viewModelScope.launch {
        val samples = (1..count).map { i ->
            val timestamp = System.currentTimeMillis()
            User(
                name = "Sample User ${timestamp % 1000 + i}",
                time = timestamp,
                email = "user${timestamp % 1000 + i}@example.com"
            )
        }
        repository.insertMultiple(samples)
        onComplete?.invoke()
    }

    fun update(user: User) = viewModelScope.launch {
        repository.update(user)
    }

    fun delete(user: User) = viewModelScope.launch {
        repository.delete(user)
    }

    fun deleteAll(onComplete: (() -> Unit)? = null) = viewModelScope.launch {
        repository.deleteAll()
        onComplete?.invoke()
    }

    fun getAll(callback: (List<User>) -> Unit) = viewModelScope.launch {
        val users = repository.getAll()
        callback(users)
    }
}

class UserViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UserViewModel::class.java)) {
            val db = AppDatabase.getDatabase(context)
            val repo = UserRepository(db.userDao())
            @Suppress("UNCHECKED_CAST")
            return UserViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

