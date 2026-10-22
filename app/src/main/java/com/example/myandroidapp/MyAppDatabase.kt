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
// version 7: raspunsul adminului la raportari (status, mesaj)
@Database(
    entities = [Post::class, UserEntity::class, ReportEntity::class, HiddenPostEntity::class],
    version = 7
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
                        "`reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`postSnippet` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL DEFAULT 'PENDING', " +
                        "`adminReply` TEXT NOT NULL DEFAULT '', `resolvedBy` TEXT NOT NULL DEFAULT '', " +
                        "`resolvedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`postId`, `reporterId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `hidden_posts` (`postId` TEXT NOT NULL, `hiddenBy` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`postId`))"
            )
        }

        /** Coloanele pentru decizia adminului la raportari (status + raspuns). */
        private fun addReportAnswerColumns(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `postSnippet` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `adminReply` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `resolvedBy` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `reports` ADD COLUMN `resolvedAt` INTEGER NOT NULL DEFAULT 0")
        }

        // 3 -> 7: din versiunea fara roluri
        private val MIGRATION_3_7 = object : Migration(3, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = createRoleTables(db)
        }

        // 4 -> 7: din versiunea de test anterioara (tabele de roluri cu alta structura)
        private val MIGRATION_4_7 = object : Migration(4, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = createRoleTables(db)
        }

        // 5 -> 6: credentialele (hash-ul parolei) intra in tabela users; rolurile si postarile raman
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `users` ADD COLUMN `passwordHash` TEXT NOT NULL DEFAULT ''")
            }
        }

        // 6 -> 7: raspunsul adminului la raportari; raportarile existente raman "in asteptare"
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = addReportAnswerColumns(db)
        }

        fun getDatabase(context: Context): MyAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    MyAppDatabase::class.java,
                    "app_database"
                ).addMigrations(MIGRATION_3_7, MIGRATION_4_7, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
