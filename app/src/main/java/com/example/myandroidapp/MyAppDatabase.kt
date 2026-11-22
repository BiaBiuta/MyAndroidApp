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
import com.example.myandroidapp.iot.data.local.PairedDeviceDao
import com.example.myandroidapp.iot.data.local.PairedDeviceEntity
import com.example.myandroidapp.todo.data.local.AuthorNoticeEntity
import com.example.myandroidapp.todo.data.local.HiddenPostEntity
import com.example.myandroidapp.todo.data.local.ModerationDao
import com.example.myandroidapp.todo.data.local.ReportEntity

import com.example.myapp.todo.data.local.ItemDao
@TypeConverters(Converters::class)
// version 3: coloana isPrivate
// version 5: utilizatori + roluri (users), raportari (reports), postari sterse (hidden_posts)
// version 6: credentialele (hash-ul parolei) in tabela users
// version 7: raspunsul adminului la raportari (status, mesaj)
// version 8: mesajele pentru autorii postarilor sterse de admin (author_notices)
// version 9: metoda de autentificare a fiecarui cont (users.provider)
// version 10: postari cu titlu, rating, data vizitei, ciorna, ascundere si stare de sincronizare
// version 11: numele locului (place)
// version 12: trackerele asociate (paired_devices), cu cheia de asociere criptata
@Database(
    entities = [Post::class, UserEntity::class, ReportEntity::class, HiddenPostEntity::class, AuthorNoticeEntity::class, PairedDeviceEntity::class],
    version = 12
)
abstract class MyAppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun userDao(): UserDao
    abstract fun moderationDao(): ModerationDao
    abstract fun pairedDeviceDao(): PairedDeviceDao

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

        // 7 -> 8: tabela cu mesajele pentru autori; restul datelor raman neatinse
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `author_notices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`recipientKey` TEXT NOT NULL, `postId` TEXT NOT NULL, `postSnippet` TEXT NOT NULL, " +
                            "`reasons` TEXT NOT NULL, `message` TEXT NOT NULL, `adminId` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `isRead` INTEGER NOT NULL)"
                )
            }
        }

        // 8 -> 9: coloana provider; conturile existente sunt create cu e-mail si parola
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `users` ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'EMAIL'")
            }
        }

        // 9 -> 10: campurile noi ale postarii; postarile existente raman, ca "sincronizate"
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "`title` TEXT NOT NULL DEFAULT ''",
                    "`rating` INTEGER NOT NULL DEFAULT 0",
                    "`visit_date` INTEGER NOT NULL DEFAULT 0",
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE'",
                    "`hidden` INTEGER NOT NULL DEFAULT 0",
                    "`syncStatus` TEXT NOT NULL DEFAULT 'SYNCED'",
                    "`syncError` TEXT NOT NULL DEFAULT ''",
                    "`isDeleted` INTEGER NOT NULL DEFAULT 0"
                ).forEach { column -> db.execSQL("ALTER TABLE `posts` ADD COLUMN $column") }
                // postarile din coada (create offline cu versiunea veche) devin "Pending"
                db.execSQL("UPDATE `posts` SET `syncStatus` = 'PENDING' WHERE `isNotSaved` = 1")
            }
        }

        // 10 -> 11: numele locului (harta OpenStreetMap in loc de Google Maps)
        // 11 -> 12: trackerele IoT asociate (cheia de asociere e criptata cu Keystore inainte de salvare)
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `paired_devices` (`id` TEXT NOT NULL, `ownerKey` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, `model` TEXT NOT NULL, `firmware` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                            "`identityPublicKey` TEXT NOT NULL, `pairingKey` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                            "`pairedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `place` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * La fiecare deschidere a bazei de date: pozele mai mari de ~1 MB (text Base64) se golesc.
         * SQLite pe Android nu poate citi un rand mai mare de ~2 MB ("Row too big to fit into
         * CursorWindow"), iar o singura postare cu poza uriasa (ex. salvata de o versiune veche,
         * inainte ca pozele sa fie micsorate) ar opri aplicatia la fiecare pornire.
         * length() se calculeaza in SQLite, fara sa citeasca randul in memorie.
         */
        private val REPAIR_OVERSIZED_PHOTOS = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE Posts SET photo = '' WHERE length(photo) > 1000000")
            }
        }

        fun getDatabase(context: Context): MyAppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    MyAppDatabase::class.java,
                    "app_database"
                ).addMigrations(MIGRATION_3_7, MIGRATION_4_7, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigration()
                    .addCallback(REPAIR_OVERSIZED_PHOTOS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
