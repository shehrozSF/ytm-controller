# R8 rules for release builds.
#
# Nothing here needs special keeping: JSON is parsed by hand with org.json (no reflection),
# and the manifest's activity/service plus Compose and Play Services ship their own rules.

# Release builds drop verbose/debug/info logging entirely (song titles and listening history
# never reach logcat); warnings and errors stay so real failures remain diagnosable.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers so crash stack traces stay readable; R8's mapping file
# (app/build/outputs/mapping/release/mapping.txt) turns the renamed names back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
