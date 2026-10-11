# Spotter release rules (R8). Add a rule only with the failure it fixes written next to it.

# --- NewPipeExtractor (YouTube) ---------------------------------------------------------------------------------
# It runs YouTube's player script with the Rhino JavaScript engine and reads protobuf messages by reflection.
# R8 failed with "Missing class java.beans.* / javax.script.*" (desktop-only classes Rhino mentions but never needs here).
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.engine.RhinoScriptEngineFactory
-dontwarn org.mozilla.javascript.tools.**
# Rhino looks classes up by name at run time.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
# Relative-time patterns are loaded by name.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# Protobuf-lite messages are accessed reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
