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
import com.example.myandroidapp.community.data.FollowDao
import com.example.myandroidapp.community.data.FollowEntity
import com.example.myandroidapp.community.data.SavedPostDao
import com.example.myandroidapp.community.data.SavedPostEntity
import com.example.myandroidapp.community.data.TripDao
import com.example.myandroidapp.community.data.TripEntity
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
// version 13: poza ca fisier (photoPath / photo_url), aprecieri si comentarii (numere de la server)
// version 14: galerie de poze, calatorii (trips), urmariri (following), postari salvate (saved_posts)
@Database(
    entities = [Post::class, UserEntity::class, ReportEntity::class, HiddenPostEntity::class, AuthorNoticeEntity::class, PairedDeviceEntity::class,
        TripEntity::class, FollowEntity::class, SavedPostEntity::class],
    version = 14
)
abstract class MyAppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun userDao(): UserDao
    abstract fun moderationDao(): ModerationDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun tripDao(): TripDao
    abstract fun followDao(): FollowDao
    abstract fun savedPostDao(): SavedPostDao

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
        // 13 -> 14: galerie, calatorii, urmariri, postari salvate
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `gallery` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `trip_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (`id` TEXT NOT NULL, `ownerKey` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`description` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, " +
                            "`updatedAt` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `syncError` TEXT NOT NULL, " +
                            "`isDeleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `following` (`ownerKey` TEXT NOT NULL, `userId` TEXT NOT NULL, " +
                            "PRIMARY KEY(`ownerKey`, `userId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_posts` (`ownerKey` TEXT NOT NULL, `postId` TEXT NOT NULL, " +
                            "`savedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerKey`, `postId`))"
                )
            }
        }

        // 12 -> 13: poza ca fisier local + adresa ei pe server; aprecieri si comentarii
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `photoPath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `photo_url` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `like_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `liked_by_me` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `posts` ADD COLUMN `comment_count` INTEGER NOT NULL DEFAULT 0")
            }
        }

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
                ).addMigrations(MIGRATION_3_7, MIGRATION_4_7, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration()
                    .addCallback(REPAIR_OVERSIZED_PHOTOS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
