# Instrumentation shares the app's library runtime but calls APIs that production
# code does not use (for example androidx.tracing.Trace and kotlin.LazyKt).
# Preserve that runtime only here; application export code still goes through R8.
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Exercise the in-memory export entry point even though production screens export
# through the repository snapshot. JSON record fields remain subject to R8.
-keep,allowobfuscation class com.andreacovelli.whispertranscriber.history.HistoryExportFormatKt {
    public static *** buildHistoryExport(...);
}
