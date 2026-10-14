# kotlinx.serialization: keep generated serializers for the data models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.overmap.crate.** { *** Companion; }
-keepclasseswithmembers class com.overmap.crate.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.overmap.crate.**$$serializer { *; }
