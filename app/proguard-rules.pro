-dontwarn javax.lang.model.**

# Keep termux terminal-emulator JNI (accessed via reflection)
-keep class com.termux.terminal.JNI { *; }
-keep class com.termux.terminal.** { *; }
