package com.ming.focusplan.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, priority DESC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>
    @Insert suspend fun insert(task: TaskEntity): Long
    @Update suspend fun update(task: TaskEntity)
    @Delete suspend fun delete(task: TaskEntity)
    @Query("UPDATE tasks SET subject = '未分类' WHERE subject = :label")
    suspend fun moveLabelToUncategorized(label: String): Int
}

@Dao
interface TaskLabelDao {
    @Query("SELECT * FROM task_labels ORDER BY createdAt, name")
    fun observeAll(): Flow<List<TaskLabelEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(label: TaskLabelEntity): Long
    @Query("DELETE FROM task_labels WHERE name = :name")
    suspend fun deleteByName(name: String): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_blocks WHERE startAt < :end AND endAt > :start ORDER BY startAt")
    fun observeBetween(start: Long, end: Long): Flow<List<ScheduleBlockEntity>>
    @Insert suspend fun insert(block: ScheduleBlockEntity): Long
    @Insert suspend fun insertAll(blocks: List<ScheduleBlockEntity>)
    @Query("SELECT taskId FROM schedule_blocks WHERE taskId IS NOT NULL")
    fun observeScheduledTaskIds(): Flow<List<Long>>
    @Query("SELECT taskId FROM schedule_blocks WHERE taskId IS NOT NULL")
    suspend fun getScheduledTaskIds(): List<Long>
    @Query("SELECT COUNT(*) FROM schedule_blocks WHERE taskId = :taskId")
    suspend fun countByTaskId(taskId: Long): Int
    @Query("SELECT COUNT(*) FROM schedule_blocks WHERE startAt < :end AND endAt > :start")
    suspend fun countOverlapping(start: Long, end: Long): Int
    @Query("SELECT * FROM schedule_blocks WHERE startAt < :end AND endAt > :start ORDER BY startAt")
    suspend fun getBetween(start: Long, end: Long): List<ScheduleBlockEntity>
    @Query("UPDATE schedule_blocks SET title = :title, priority = :priority, endAt = startAt + :durationMs WHERE taskId = :taskId")
    suspend fun updateTaskDetails(taskId: Long, title: String, priority: Int, durationMs: Long)
    @Query("DELETE FROM schedule_blocks WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: Long)
    @Query("DELETE FROM schedule_blocks WHERE taskId IN (SELECT id FROM tasks WHERE completed = 0) AND startAt < :end AND endAt > :start")
    suspend fun clearIncompleteTaskBlocksBetween(start: Long, end: Long): Int
    @Query("DELETE FROM schedule_blocks WHERE startAt >= :start AND startAt < :end AND isFixed = 0")
    suspend fun clearFlexibleBetween(start: Long, end: Long)
    @Delete suspend fun delete(block: ScheduleBlockEntity)
}

@Dao
interface ModelProfileDao {
    @Query("SELECT * FROM model_profiles ORDER BY enabled DESC, role")
    fun observeAll(): Flow<List<ModelProfileEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: ModelProfileEntity): Long
    @Delete suspend fun delete(profile: ModelProfileEntity)
}
