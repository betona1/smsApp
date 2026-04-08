package com.example.smsreceiverapp.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CSPhoneDao {
    @Query("SELECT * FROM cs_phone")
    fun getAll(): List<CSPhoneEntity>

    @Query("SELECT * FROM cs_phone WHERE csphone_number = :myNumber")
    fun getMine(myNumber: String): List<CSPhoneEntity>

    @Query("DELETE FROM cs_phone")
    fun clearALL()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertALL(list: List<CSPhoneEntity>)
    // ✅ 이게 반드시 있어야 update 동작함!

    @Update
    suspend fun update(entity: CSPhoneEntity)

    @Delete
    fun delete(entity: CSPhoneEntity)
}