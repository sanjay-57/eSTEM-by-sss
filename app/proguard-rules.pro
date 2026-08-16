# Referenced by app/build.gradle.kts. Empty of app rules on purpose: release does not minify yet,
# and the native engine is reached through JNI names that R8 cannot see, so turning shrinking on
# needs the keep rules below to be checked against a real release build first.

# The JNI entry points are looked up by name from C++ and have no Java-side callers.
-keepclasseswithmembernames class com.sss.estem.audio.** {
    native <methods>;
}
