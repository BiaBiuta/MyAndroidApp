package com.example.myapp


import  com.example.myandroidapp.todo.data.Converters
import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myandroidapp.auth.data.local.UserDao
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.local.HiddenPostEntity
import com.example.myandroidapp.todo.data.local.ModerationDao
import com.example.myandroidapp.todo.data.local.ReportEntity

import com.example.myapp.todo.data.local.ItemDao
@TypeConverters(Converters::class)
// version 3: coloana isPrivate
// version 5: utilizatori + roluri (users), raportari (reports), postari sterse (hidden_posts)
// version 6: credentialele (hash-ul parolei) in tabela users
@Database(
    entities = [Post::class, UserEntity::class, ReportEntity::class, HiddenPostEntity::class],
    version = 6
)
abstract class MyAppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun userDao(): UserDao
    abstract fun moderationDao(): ModerationDao

    companion object {
        @Volatile
        private var INSTANCE: MyAppDatabase? = null

        /**
         * Creeaza tabelele pentru roluri. Le sterge intai daca exista deja: pe unele telefoane
         * au ramas tabele cu acelasi nume, dar alta structura, dintr-o versiune de test anterioara.
         * Tabela posts NU se atinge, deci postarile existente (inclusiv cele private) raman.
         */
        private fun createRoleTables(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `users`")
            db.execSQL("DROP TABLE IF EXISTS `reports`")
            db.execSQL("DROP TABLE IF EXISTS `hidden_posts`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `users` (`email` TEXT NOT NULL, `ownerKey` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `role` TEXT NOT NULL, `passwordHash` TEXT NOT NULL DEFAULT '', " +
                        "`isBlocked` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `lastLoginAt` INTEGER NOT NULL, PRIMARY KEY(`email`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reports` (`postId` TEXT NOT NULL, `reporterId` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`postId`, `reporterId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `hidden_posts` (`postId` TEXT NOT NULL, `hiddenBy` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`postId`))"
            )
        }

        // 3 -> 6: din versiunea fara roluri
        private val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = createRoleTables(db)
        }

        // 4 -> 6: din versiunea de test anterioara (tabele de roluri cu alta structura)
        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = createRoleTables(db)
        }

        // 5 -> 6: credentialele (hash-ul parolei) intra in tabela users; rolurile si postarile raman
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `users` ADD COLUMN `passwordHash` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): MyAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    MyAppDatabase::class.java,
                    "app_database"
                ).addMigrations(MIGRATION_3_6, MIGRATION_4_6, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
