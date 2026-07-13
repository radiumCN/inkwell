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

# Rhino（JS 规则引擎）：内部反射构造 native 包装类
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
