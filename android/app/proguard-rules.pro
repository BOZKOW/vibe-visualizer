# kuromoji finds its dictionary next to its own classes (getResourceAsStream): keep their names and packages
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# readable stack traces in the error log on your server
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
