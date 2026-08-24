package com.gadware.drivebackup

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gadware.drivebackup.databinding.ActivityInsertDataBinding
import com.gadware.drivebackup.room.User
import com.gadware.drivebackup.room.UserAdapter
import com.gadware.drivebackup.room.UserViewModel
import com.gadware.drivebackup.room.UserViewModelFactory
import kotlinx.coroutines.launch

class InsertDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsertDataBinding
    private lateinit var viewModel: UserViewModel
    private lateinit var adapter: UserAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsertDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val factory = UserViewModelFactory(this)
        viewModel = ViewModelProvider(this, factory)[UserViewModel::class.java]

        setupUI()
        observeData()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        adapter = UserAdapter { user ->
            showDeleteSingleDialog(user)
        }

        binding.recyclerViewUsers.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewUsers.adapter = adapter

        binding.btnInsert.setOnClickListener {
            val name = binding.etName.text?.toString()?.trim() ?: ""
            val email = binding.etEmail.text?.toString()?.trim() ?: ""

            if (name.isBlank() || email.isBlank()) {
                Toast.makeText(this, "Please enter both name and email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newUser = User(
                name = name,
                email = email,
                time = System.currentTimeMillis()
            )

            viewModel.insert(newUser) {
                binding.etName.text?.clear()
                binding.etEmail.text?.clear()
                binding.etName.clearFocus()
                binding.etEmail.clearFocus()
                Toast.makeText(this, "User inserted successfully", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddSamples.setOnClickListener {
            viewModel.insertSampleUsers(3) {
                Toast.makeText(this, "3 sample records added", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearAll.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun showDeleteSingleDialog(user: User) {
        AlertDialog.Builder(this)
            .setTitle("Delete User")
            .setMessage("Are you sure you want to delete ${user.name}?")
            .setPositiveButton("Delete") { dialog, _ ->
                viewModel.delete(user)
                dialog.dismiss()
                Toast.makeText(this, "Deleted ${user.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Records")
            .setMessage("Are you sure you want to delete all user records from the local database?")
            .setIcon(com.gadware.dcadm.R.drawable.outline_warning_amber_24)
            .setPositiveButton("Clear All") { dialog, _ ->
                viewModel.deleteAll {
                    Toast.makeText(this, "All records deleted", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.usersState.collect { users ->
                    adapter.submitList(users)

                    val count = users.size
                    binding.chipRecordCount.text = "$count ${if (count == 1) "record" else "records"}"

                    if (users.isEmpty()) {
                        binding.layoutEmptyState.visibility = View.VISIBLE
                        binding.recyclerViewUsers.visibility = View.GONE
                    } else {
                        binding.layoutEmptyState.visibility = View.GONE
                        binding.recyclerViewUsers.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
