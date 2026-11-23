package com.example.myandroidapp.todo.data

/**
 * Camp care exista doar pe telefon (ex. calea locala a pozei): nu se trimite la server.
 * Folosit de Gson (Api.gson) printr-o strategie de excludere; Room il salveaza normal.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class LocalOnly
