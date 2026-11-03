package com.mymesag.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppFilterDao {

    @Query("SELECT * FROM app_filter ORDER BY appName")
    fun observeAll(): Flow<List<AppFilterEntity>>

    @Query("SELECT enabled FROM app_filter WHERE pkg = :pkg")
    suspend fun isEnabled(pkg: String): Boolean?

    @Upsert
    suspend fun upsert(entity: AppFilterEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AppFilterEntity>)
}
