# ONNX Runtime (Beat This! beat tracking): its JNI code finds these classes, fields and methods
# by name; renamed or removed by R8 the native run() aborts the process.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**
