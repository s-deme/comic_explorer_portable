# 7-Zip-JBinding has no consumer rules. Its JNI bridge reads fields and invokes
# stream/callback methods by name; keep those names without retaining classes.
-keepclassmembers class net.sf.sevenzipjbinding.** {
    <fields>;
}
-keepnames interface net.sf.sevenzipjbinding.I*
-keepclassmembernames class * implements net.sf.sevenzipjbinding.I* {
    <methods>;
}

# These providers are optional in the included libraries and are absent from
# the Debug APK too: PDFBox reports a recoverable error for JPX without JP2,
# Commons Compress' Zstd stream is unused, and SMBJ falls back to NTLM when
# its reflection-loaded EL/SPNEGO integrations are unavailable.
-dontwarn com.gemalto.jp2.**
-dontwarn com.github.luben.zstd.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
