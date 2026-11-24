package com.example.myandroidapp.todo.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    @TypeConverter
    fun fromLocation(location: Location): String {
        return Gson().toJson(location)
    }

    @TypeConverter
    fun toLocation(locationString: String): Location {
        val type = object : TypeToken<Location>() {}.type
        return Gson().fromJson(locationString, type)
    }

    // galeria unei postari: liste de cai locale / adrese "/photos/<id>", salvate ca JSON
    @TypeConverter
    fun fromStringList(values: List<String>): String = Gson().toJson(values)

    @TypeConverter
    fun toStringList(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList()
        else Gson().fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}
