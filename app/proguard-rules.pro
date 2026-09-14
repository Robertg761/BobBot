-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bobbot.**$$serializer { *; }
-keepclassmembers class com.bobbot.** { *** Companion; }
-keepclasseswithmembers class com.bobbot.** { kotlinx.serialization.KSerializer serializer(...); }
