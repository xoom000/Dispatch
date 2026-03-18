# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-defaults.txt file.

# sherpa-onnx: Keep all classes — JNI native code calls into these via reflection
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep Kotlin Function interfaces used as JNI callbacks
-keep class kotlin.jvm.functions.** { *; }

# Prevent R8 from mangling lambda classes that cross JNI boundary
-keep class dev.digitalgnosis.dispatch.tts.TtsEngine$* { *; }
