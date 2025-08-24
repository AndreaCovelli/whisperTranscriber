# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes Annotation
-keep class com.andreacovelli.whispertranscriber.UploadResponse { <fields>; }
-keep class com.andreacovelli.whispertranscriber.TranscriptRequest { <fields>; }
-keep class com.andreacovelli.whispertranscriber.TranscriptResponse { <fields>; }
-keep class com.andreacovelli.whispertranscriber.data.** { *; }
-keep class androidx.room.* { *; }

-keep class com.google.auth.** { *; }
-keep class com.google.api.client.** { *; }

-keep class com.google.genai.** { *; }
-dontwarn com.google.api.client.*
-dontwarn com.google.auth.**
-dontwarn com.google.api.core.**
-dontwarn com.google.common.**
-dontwarn org.threeten.bp.**
-dontwarn javax.inject.**

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.*

-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.NamingException
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid