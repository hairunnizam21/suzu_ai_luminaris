# Consumer rules pulled in by any APK that depends on :shared.
# kotlinx-serialization metadata must survive R8.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
