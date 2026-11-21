# TuxunDown release rules. The app code itself needs no keeps beyond
# the library consumer rules (Compose / coroutines / core-ktx ship
# their own); what follows is belt-and-braces for framework callbacks
# that R8 cannot always trace.

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# v2.0.0 slimming: the old blanket keep on TaskRecord members is
# GONE. It dated from a belt-and-braces phase, but task persistence
# is hand-written org.json with the keys as string literals in
# TaskStore — nothing anywhere reads TaskRecord reflectively (no
# Gson/Moshi/kotlinx.serialization/Class.forName in the codebase),
# so R8 may shrink and rename its members freely; saved task files
# stay readable because the KEYS, not the field names, are the
# format.

# WebView sniffing hooks are invoked from the framework side.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
