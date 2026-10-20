# JNI looks up these classes/methods by their original names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# JNA desktop helpers reference AWT, which Android does not have; they are never called.
-dontwarn java.awt.**
