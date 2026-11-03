package com.mymesag.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 每个应用的同步启用状态（项目书 8.2：Room 存每个 App 的启用状态）。 */
@Entity(tableName = "app_filter")
data class AppFilterEntity(
    @PrimaryKey val pkg: String,
    val appName: String,
    val enabled: Boolean
)
