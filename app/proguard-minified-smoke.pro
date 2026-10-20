# Instrumentation shares the app's library runtime but calls APIs that production
# code does not use (for example androidx.tracing.Trace and kotlin.LazyKt).
# Preserve that runtime only here; application export code still goes through R8.
# Let R8 remove unused generated icons instead of compiling the entire catalog.
-keep class !androidx.compose.material.icons.**,androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Exercise the in-memory export entry point even though production screens export
# through the repository snapshot. JSON record fields remain subject to R8.
-keep,allowobfuscation class com.andreacovelli.whispertranscriber.history.HistoryExportFormatKt {
    public static *** buildHistoryExport(...);
}

# The export test wires the repository by hand, while production reaches these only through
# Hilt bindings. Keep that test-only path so R8 cannot devirtualize or drop it; names still obfuscate.
-keep,allowobfuscation class com.andreacovelli.whispertranscriber.data.AppDatabase {
    public abstract *** transcriptionHistoryDao();
    public abstract *** transcriptionJobDao();
}
-keep,allowobfuscation class com.andreacovelli.whispertranscriber.data.RoomHistoryRepository {
    public <init>(...);
    public *** exportHistory(...);
    public *** insert(...);
    public *** deleteById(...);
    public *** hasHistory(...);
}
-keep,allowobfuscation class com.andreacovelli.whispertranscriber.data.TranscriptionHistory {
    public *;
}
