# Views, die in XML als com.highfly.logbook.BarChartView etc. eingebunden werden.
# Ohne diese Regel findet der LayoutInflater die Klasse nach dem Umbenennen nicht.
-keep public class com.highfly.logbook.** extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context);
}

# Fragments, die der Navigation-Graph ueber den Klassennamen erzeugt.
-keep public class com.highfly.logbook.** extends androidx.fragment.app.Fragment

# osmdroid liefert keine eigenen Proguard-Regeln mit und greift an einigen Stellen
# ueber Reflection auf das Konfigurationssystem zu.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Stacktraces aus dem CrashLogger sollen lesbar bleiben.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
