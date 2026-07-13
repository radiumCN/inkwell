# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.radium.inkwell.** {
    *** Companion;
}
-keepclasseswithmembers class com.radium.inkwell.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# json-path
-dontwarn com.jayway.jsonpath.**
-dontwarn org.slf4j.**
