# Shoten itself uses no reflection.

# ML Kit creates its components by reflection from manifest metadata. Its own rule keeps the
# classes but not their constructors, which R8's full mode then removes (crash on start).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Entry points of ML Kit's bundled OCR models (offline flavor) are created by reflection too.
-keep @com.google.android.gms.common.util.DynamiteApi class * { <init>(); }
