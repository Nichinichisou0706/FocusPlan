package com.ming.focusplan.data

import android.content.Context
import com.ming.focusplan.assistant.ApiKeyStore
import com.ming.focusplan.assistant.AssistantPreferences

class AppContainer(context: Context) {
    val database = AppDatabase.create(context)
    val tasks = database.taskDao()
    val labels = database.taskLabelDao()
    val schedule = database.scheduleDao()
    val models = database.modelProfileDao()
    val apiKeys = ApiKeyStore(context)
    val assistantPreferences = AssistantPreferences(context)
}
