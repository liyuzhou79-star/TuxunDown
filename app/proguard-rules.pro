# TuxunDown release rules. The app code itself needs no keeps beyond
# the library consumer rules (Compose / coroutines / core-ktx ship
# their own); what follows is belt-and-braces for framework callbacks
# that R8 cannot always trace.

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Task persistence is hand-written org.json code (framework class,
# not obfuscated); keep our data holders' field names stable anyway
# so saved task files stay readable across versions.
-keepclassmembers class com.tuxundown.android.core.TaskRecord { *; }

# WebView sniffing hooks are invoked from the framework side.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
