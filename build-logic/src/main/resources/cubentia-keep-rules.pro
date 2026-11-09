# R8 keep rules owned by build-logic (cubentia.release). Libraries (Hilt, Compose, AndroidX)
# ship their own consumer rules; add app-specific rules here, never in :app.

# Keep line numbers for readable Android vitals stack traces; hide original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
