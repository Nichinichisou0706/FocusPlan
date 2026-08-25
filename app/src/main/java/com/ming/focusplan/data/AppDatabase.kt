package com.ming.focusplan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TaskEntity::class, TaskLabelEntity::class, ScheduleBlockEntity::class, ModelProfileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskLabelDao(): TaskLabelDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun modelProfileDao(): ModelProfileDao

    companion object {
        private val defaultLabels = listOf("数学", "英语", "政治", "专业课")

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_labels` (`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`))")
                defaultLabels.forEachIndexed { index, label ->
                    db.execSQL("INSERT OR IGNORE INTO task_labels(name, createdAt) VALUES(?, ?)", arrayOf(label, index.toLong()))
                }
                db.execSQL("INSERT OR IGNORE INTO task_labels(name, createdAt) SELECT subject, MIN(createdAt) FROM tasks WHERE TRIM(subject) != '' AND subject != '未分类' GROUP BY subject")
            }
        }

        private val seedLabels = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                defaultLabels.forEachIndexed { index, label ->
                    db.execSQL("INSERT OR IGNORE INTO task_labels(name, createdAt) VALUES(?, ?)", arrayOf(label, index.toLong()))
                }
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context, AppDatabase::class.java, "focus-plan.db"
        ).addMigrations(migration1To2).addCallback(seedLabels).build()
    }
}
