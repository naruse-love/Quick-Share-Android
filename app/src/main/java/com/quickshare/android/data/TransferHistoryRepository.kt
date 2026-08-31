package com.quickshare.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.quickshare.android.model.TransferTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ITransferHistoryRepository {
    val completedHistory: StateFlow<List<TransferTask>>
    val failedHistory: StateFlow<List<TransferTask>>

    fun addCompletedTask(task: TransferTask)
    fun addFailedTask(task: TransferTask)
    fun clearHistory()
}

class TransferHistoryRepository(
    context: Context,
    prefsName: String = "quickshare_transfer_history"
) : ITransferHistoryRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _completedHistory = MutableStateFlow(loadTasks(KEY_COMPLETED))
    override val completedHistory: StateFlow<List<TransferTask>> = _completedHistory.asStateFlow()

    private val _failedHistory = MutableStateFlow(loadTasks(KEY_FAILED))
    override val failedHistory: StateFlow<List<TransferTask>> = _failedHistory.asStateFlow()

    private fun loadTasks(key: String): List<TransferTask> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TransferTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveTasks(key: String, tasks: List<TransferTask>) {
        val json = gson.toJson(tasks.take(100))
        prefs.edit().putString(key, json).apply()
    }

    override fun addCompletedTask(task: TransferTask) {
        val list = _completedHistory.value.toMutableList()
        list.removeAll { it.id == task.id }
        list.add(0, task)
        _completedHistory.value = list
        saveTasks(KEY_COMPLETED, list)
    }

    override fun addFailedTask(task: TransferTask) {
        val list = _failedHistory.value.toMutableList()
        list.removeAll { it.id == task.id }
        list.add(0, task)
        _failedHistory.value = list
        saveTasks(KEY_FAILED, list)
    }

    override fun clearHistory() {
        _completedHistory.value = emptyList()
        _failedHistory.value = emptyList()
        prefs.edit().remove(KEY_COMPLETED).remove(KEY_FAILED).apply()
    }

    companion object {
        private const val KEY_COMPLETED = "key_completed_tasks"
        private const val KEY_FAILED = "key_failed_tasks"
    }
}
