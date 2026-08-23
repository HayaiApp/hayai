-dontobfuscate

-keep class eu.kanade.tachiyomi.source.** { public protected *; } # Avoid access modification
-keep,allowoptimization class eu.kanade.** { public protected *; }
-keep,allowoptimization class dev.ahmedmohamed.hayai.** { public protected *; }
-keep,allowoptimization class tachiyomi.** { public protected *; }

# Keep common dependencies used in extensions
-keep class androidx.preference.** { public protected *; }
-keep class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep class kotlinx.serialization.** { public protected *; }
-keep class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep class org.jsoup.** { public protected *; }
-keep,allowoptimization class com.google.gson.** { public protected *; }
-keep,allowoptimization class com.dokar.quickjs.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keepattributes Signature
-keep,allowoptimization class * extends uy.kohesive.injekt.api.TypeReference
-keep,allowoptimization class eu.davidea.flexibleadapter.** { public protected *; }
-keep class io.requery.android.database.** { public protected *; }
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }

# From extensions-lib
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.NetworkHelper { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.RequestsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.AppInfo { public protected *; }

-keep class com.hippo.image.** { *; }
-keep interface com.hippo.image.** { *; }
-dontwarn nucleus.view.NucleusActionBarActivity

# Design library
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }
-keep interface com.google.android.material.** { *; }
-keep public class com.google.android.material.R$* { *; }

##---------------Begin: proguard configuration for RxJava 1.x  ----------
-dontwarn sun.misc.**

-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
   long producerIndex;
   long consumerIndex;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}

-dontnote rx.internal.util.PlatformDependent
##---------------End: proguard configuration for RxJava 1.x  ----------

-keepclassmembers class * implements java.io.Serializable {
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Provided by the OEM at runtime when supported
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

##---------------Begin: proguard configuration for kotlinx.serialization  ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class eu.kanade.**$$serializer { *; }
-keep,includedescriptorclasses class dev.ahmedmohamed.hayai.**$$serializer { *; }
-keepclassmembers class eu.kanade.** {
    *** Companion;
}
-keepclassmembers class dev.ahmedmohamed.hayai.** {
    *** Companion;
}
-keepclasseswithmembers class eu.kanade.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.ahmedmohamed.hayai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** {
    <methods>;
}
##---------------End: proguard configuration for kotlinx.serialization  ----------

# Firebase
-keep class com.google.firebase.installations.** { *; }
-keep interface com.google.firebase.installations.** { *; }

# Methods called by Shizuku only
-keepclassmembers class eu.kanade.tachiyomi.extension.shizuku.ShellInterface {
    public <init>();
    public void destroy();
}
