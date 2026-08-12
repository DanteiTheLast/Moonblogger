# MoonBlogger — reglas ProGuard/R8.
#
# v1: el release se compila con isMinifyEnabled = false, así que estas reglas
# no se aplican todavía. Se dejan documentadas para cuando se active la
# ofuscación:
#
# - Retrofit 2.x/3.x y OkHttp 4.x/5.x incluyen reglas R8 embebidas.
# - kotlinx.serialization requiere reglas específicas; consulta:
#   https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/json/README.md#android
#
# -keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
# -keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
# -keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
# -keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
