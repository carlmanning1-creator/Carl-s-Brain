package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BucketDao {

    @Query("SELECT * FROM buckets ORDER BY sortOrder ASC, name ASC")
    fun getAllBuckets(): Flow<List<BucketEntity>>

    @Query("SELECT * FROM buckets WHERE isVault = 0 ORDER BY sortOrder ASC, name ASC")
    fun getNonVaultBuckets(): Flow<List<BucketEntity>>

    @Query("SELECT * FROM buckets WHERE id = :id")
    suspend fun getBucketById(id: Long): BucketEntity?

    @Query("SELECT * FROM buckets WHERE name = :name LIMIT 1")
    suspend fun getBucketByName(name: String): BucketEntity?

    @Query("SELECT COUNT(*) FROM buckets")
    suspend fun getBucketCount(): Int

    @Query("SELECT COUNT(*) FROM buckets WHERE isVault = 0")
    suspend fun getNonVaultBucketCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBucket(bucket: BucketEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBuckets(buckets: List<BucketEntity>)

    @Update
    suspend fun updateBucket(bucket: BucketEntity)

    @Delete
    suspend fun deleteBucket(bucket: BucketEntity)
}
