//package com.example.myapp
//
//
//import  com.example.myandroidapp.todo.data.Converters
//import android.content.Context
//
//import androidx.room.Database
//import androidx.room.Room
//import androidx.room.RoomDatabase
//import androidx.room.TypeConverters
//import com.example.myandroidapp.todo.data.Post
//
//import com.example.myapp.todo.data.local.ItemDao
//@TypeConverters(Converters::class)
//@Database(entities = arrayOf(Post::class), version = 2)
//abstract class AppDatabase : RoomDatabase() {
//    abstract fun itemDao(): ItemDao
//
//    companion object {
//        @Volatile
//        private var INSTANCE: MyAppDatabase? = null
//
//        fun getDatabase(context: Context): MyAppDatabase {
//            return INSTANCE ?: synchronized(this) {
//                val instance = Room.databaseBuilder(
//                    context,
//                    MyAppDatabase::class.java,
//                    "app_database"
//                ).fallbackToDestructiveMigration()
//                    .build()
//                INSTANCE = instance
//                instance
//            }
//        }
//    }
//}
