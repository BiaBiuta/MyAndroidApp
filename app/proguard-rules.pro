# =====================================================================================
# WanderLog - reguli R8 pentru build-ul RELEASE (isMinifyEnabled = true)
#
# R8 face trei lucruri: sterge codul nefolosit, OFUSCHEAZA (redenumeste clasele / metodele in
# a, b, c...) si optimizeaza. Ofuscarea ingreuneaza decompilarea APK-ului, dar codul care
# foloseste REFLECTIE (Gson, Retrofit) trebuie sa-si pastreze numele, altfel JSON-ul primit de
# la server nu se mai potriveste cu campurile clasei. Regulile de mai jos pastreaza DOAR atat.
# =====================================================================================

# ---- Atribute de care au nevoie Gson / Retrofit (tipuri generice, adnotari) ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
# numarul liniei in stack trace (fara numele fisierului sursa)
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Modelele care devin JSON (server, WebSocket, tracker, DataStore, Room converters) ----
# toate sunt in pachete "data" (todo.data, auth.data, community.data, core.data, iot.data...)
-keep class com.example.myandroidapp.**.data.** { *; }
# manifestul actualizarilor de tracker (semnatura se verifica pe textul JSON exact)
-keep class com.example.myandroidapp.iot.update.UpdateManifest { *; }
# cererea / raspunsul de schimbare a parolei (definite langa ecranul de profil)
-keep class com.example.myandroidapp.profile.Me { *; }
-keep class com.example.myandroidapp.profile.PasswordChange { *; }
-keep interface com.example.myandroidapp.profile.ProfileService { *; }

# ---- Gson (versiunea folosita de converter-gson 2.9 nu are reguli proprii) ----
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.**

# ---- Retrofit cu functii suspend (necesar cu R8 "full mode", implicit din AGP 8) ----
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Logging: in release, apelurile de depanare dispar complet din cod ----
# (SafeLog oricum nu le scrie in release; asa nici textul mesajelor nu mai ramane in APK)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class com.example.myandroidapp.core.logging.SafeLog {
    public void d(...);
    public void i(...);
}
