# Nazwy stałych enumów trafiają do SharedPreferences i wracają przez valueOf(),
# więc nie mogą zostać zmienione przez R8.
-keepclassmembers enum pl.piny.waga.** { *; }

# Widoki tworzone z XML-a są instancjonowane refleksyjnie przez LayoutInflater.
-keep class pl.piny.waga.PanView { public <init>(android.content.Context, android.util.AttributeSet); }
-keep class pl.piny.waga.MeterView { public <init>(android.content.Context, android.util.AttributeSet); }
