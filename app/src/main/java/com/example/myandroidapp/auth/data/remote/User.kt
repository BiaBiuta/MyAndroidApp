package com.example.myandroidapp.auth.data.remote
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

//equals()/hashCode() pair.- daca am date declarate ca parametrii secundari
//si nu in constructor o sa vada obiectele la fel
// exemplu cu oameni cu acelasi nume dar varsta diferita
//equals daca varsta e declarata doar in bloc o sa
//il vada la fel
//toString() of the form "User(name=John, age=42)".
//
//componentN() functions corresponding to the properties in their order of declaration.
//
//copy() function (see below).
@Parcelize
data class User (val email: String, val password:String,val id:Int=0):Parcelable

