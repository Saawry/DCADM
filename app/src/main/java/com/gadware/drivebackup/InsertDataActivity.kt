package com.gadware.drivebackup

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gadware.drivebackup.databinding.ActivityInsertDataBinding
import com.gadware.drivebackup.room.AppDatabase
import com.gadware.drivebackup.room.User
import com.gadware.drivebackup.room.UserRepository
import com.gadware.drivebackup.room.UserViewModel

class InsertDataActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInsertDataBinding
    private lateinit var userViewModel: UserViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsertDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val db = AppDatabase.getDatabase(this)
        val repository = UserRepository(db.userDao())
        userViewModel = UserViewModel(repository)
        binding.btnInsert.setOnClickListener {
            setData()
        }
        binding.btnGet.setOnClickListener {
            getData()
        }
    }
    private fun setData() {

        // Insert
        userViewModel.insert(User(name = "Alice",time= System.currentTimeMillis(), email = "alice@email.com"))


    }
    private fun getData() {


        // Get all users
        userViewModel.getAll { users ->
            users.forEach {
                Toast.makeText(this, "\"User: ${it.name}, Email: ${it.email}\"", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "User: ${it.name}, Email: ${it.email}")
            }
        }
    }
}


