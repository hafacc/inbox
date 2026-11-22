# kotlinx.serialization keeps its generated serializers through its own consumer rules.

# Readable stack traces in release logcat (R8 still renames symbols; the
# generated mapping.txt deobfuscates fully).
-keepattributes SourceFile,LineNumberTable
