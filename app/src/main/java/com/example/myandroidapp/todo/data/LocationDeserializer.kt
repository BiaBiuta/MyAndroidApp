package com.example.myandroidapp.todo.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

class LocationDeserializer : JsonDeserializer<Location> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Location {
        // Verifică dacă elementul JSON este un șir
        if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
            val locationString = json.asString
            val coordinates = locationString.split(",")
            // Dacă locația este validă, convertește în Location
            if (coordinates.size == 2) {
                val latitude = coordinates[0].toDoubleOrNull() ?: 0.0
                val longitude = coordinates[1].toDoubleOrNull() ?: 0.0
                return Location(latitude, longitude)
            }
        }
        // Dacă nu poate fi deserializat corect, returnează locația implicită
        return Location()
    }
}