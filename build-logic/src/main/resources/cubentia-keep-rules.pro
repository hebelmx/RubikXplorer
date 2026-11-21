# R8 keep rules owned by build-logic (cubentia.release). Libraries (Hilt, Compose, AndroidX)
# ship their own consumer rules; add app-specific rules here, never in :app.

# Keep line numbers for readable Android vitals stack traces; hide original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LiteRT (com.google.ai.edge.litert) JNI bridges used by Quick Scan corner detection when corner flags are above OFF.
# Kept inert in OFF builds where LiteRT is absent from the classpath.
-keepclasseswithmembernames,includedescriptorclasses class org.tensorflow.lite.** {
    native <methods>;
}
-keep class org.tensorflow.lite.NativeInterpreterWrapper** { *; }
-keep class org.tensorflow.lite.TensorImpl** { *; }
-keep class org.tensorflow.lite.TensorFlowLite { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-keep class org.tensorflow.lite.DelegateFactory { *; }
-keep class org.tensorflow.lite.nnapi.NnApiDelegateImpl { *; }
-dontwarn org.tensorflow.lite.gpu.**
