package com.example.smsreceiverapp.ui.theme.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.smsreceiverapp.db.CSPhoneDao
import com.example.smsreceiverapp.db.CSPhoneEntity

@Database(entities = [CSPhoneEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun csPhoneDao(): CSPhoneDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cs_phone"  // DB 이름은 동일
                )
                    .fallbackToDestructiveMigration()  // ✅ 핵심 줄!
                    .build().also { INSTANCE = it }
            }
        }
    }
}