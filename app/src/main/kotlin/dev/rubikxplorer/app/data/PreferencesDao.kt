package dev.rubikxplorer.app.data
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface PreferencesDao {
    @Query("SELECT * FROM preferences WHERE id = 1")
    suspend fun load(): PreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PreferencesEntity)

    @Query("DELETE FROM preferences")
    suspend fun reset()
}
