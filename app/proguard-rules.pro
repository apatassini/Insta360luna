-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

# Tutto il codice dell'app resta intatto.
#
# Il peso dell'APK non è questa app — sono poche decine di kB — ma Compose e la libreria
# standard Kotlin, che senza R8 finiscono nel dex per intero: 25 MB di bytecode non ottimizzato.
# Tenere fuori dalla riduzione il proprio codice costa quasi nulla in dimensione ed elimina la
# classe di guasti peggiore da diagnosticare a distanza: un metodo rimosso o rinominato che si
# manifesta solo a runtime, su un dispositivo che non ho.
-keep class it.persoft.lunaultra.** { *; }

# I serializzatori generati da kotlinx.serialization vengono cercati per nome.
-keepclassmembers class it.persoft.lunaultra.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class it.persoft.lunaultra.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
