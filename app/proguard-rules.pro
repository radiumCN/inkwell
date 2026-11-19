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

# 来源脚本通过 Rhino 反射调用这些公开字段和方法；ClassShutter 还按原包名放行它们。
-keep class com.radium.inkwell.core.source.js.*Bridge,
            com.radium.inkwell.core.source.js.JsCache,
            com.radium.inkwell.core.source.js.JsRequest,
            com.radium.inkwell.core.source.js.StrResponse,
            com.radium.inkwell.core.source.js.JsElement,
            com.radium.inkwell.core.source.js.JsElements,
            com.radium.inkwell.core.source.js.JsAttribute,
            com.radium.inkwell.core.source.js.JsAttributes,
            com.radium.inkwell.core.source.js.JsIterator,
            com.radium.inkwell.core.source.js.JsConnection,
            com.radium.inkwell.core.source.js.SymmetricCrypto,
            com.radium.inkwell.core.source.js.JdkCompat,
            com.radium.inkwell.core.source.js.*Shim {
    public <fields>;
    public <methods>;
}
