-dontobfuscate

# Keep generic type signatures (required by Koin reified types and by the
# Injekt bridge classes that installed extensions still link against).
-keepattributes Signature

# Installed extensions are compiled against `source/api`, which exposes the
# koin-injekt bridge under the `uy.kohesive.injekt.*` package. The main app no
# longer references those classes directly, so R8 would strip them; keep both
# the bridge surface and any `FullTypeReference` anonymous subclasses that
# extensions create at `Injekt.get<T>()` call sites.
-keep class ** extends uy.kohesive.injekt.api.FullTypeReference { *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }

-keep,allowoptimization class eu.kanade.**
-keep,allowoptimization class tachiyomi.**
-keep,allowoptimization class yokai.**
-keep,allowoptimization class exh.**

# Keep common dependencies used in extensions
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class kotlin.time.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class com.google.gson.** { public protected *; }
-keep,allowoptimization class com.dokar.quickjs.** { public protected *; }
# Extension-facing JS engine. The `app.cash.quickjs.QuickJs` shim in this app
# (see app/src/main/java/app/cash/quickjs/) is loaded reflectively by extensions
# (Mangago, MangaHere, etc.) — no in-app code references it, so R8 would strip it.
-keep,allowoptimization class app.cash.quickjs.** { public protected *; }
-keep,allowoptimization class org.koin.** { public protected *; }
-keep,allowoptimization class eu.davidea.flexibleadapter.** { public protected *; }
-keep class io.requery.android.database.** { public protected *; }

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

# AndroidX Window reflects optional platform extension/sidecar APIs that are
# only present on devices which provide them.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

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

##---------------Begin: proguard configuration for okhttp  ----------
-keepclasseswithmembers class okhttp3.MultipartBody$Builder { *; }
##---------------End: proguard configuration for okhttp  ----------

##---------------Begin: proguard configuration for kotlinx.serialization  ----------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class yokai.**$$serializer { *; }
-keepclassmembers class yokai.** {
    *** Companion;
}
-keepclasseswithmembers class yokai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class eu.kanade.**$$serializer { *; }
-keepclassmembers class eu.kanade.** {
    *** Companion;
}
-keepclasseswithmembers class eu.kanade.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** {
    <methods>;
}

# XmlUtil
-keep public enum nl.adaptivity.xmlutil.EventType { *; }
##---------------End: proguard configuration for kotlinx.serialization  ----------

# Firebase
-keep class com.google.firebase.installations.** { *; }
-keep interface com.google.firebase.installations.** { *; }

# EXH metadata serialization
-keep class exh.metadata.metadata.** { *; }
-keep class exh.md.dto.** { *; }
