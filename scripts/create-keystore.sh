#!/usr/bin/env bash
#
# create-keystore.sh — Genera el keystore de firma del release de MoonBlogger.
#
# MoonBlogger se distribuye como APK release firmado e instalado directamente
# en el dispositivo (fuera de Play Store). Android exige que las actualizaciones
# se firmen con la MISMA clave, así que este keystore es la identidad de firma
# de la app (ver sección CUSTODIA al final).
#
# Crea:
#   - <directorio>/<archivo>.jks        keystore (NO versionar)
#   - <directorio>/keystore.properties  NO versionar; lo lee build.gradle.kts
#
# Uso:
#   ./scripts/create-keystore.sh                        # interactivo (pide contraseñas)
#   ./scripts/create-keystore.sh -n                     # no interactivo: contraseñas aleatorias
#   ./scripts/create-keystore.sh -f moon.jks -a moon -v 10000 -n
#
# Opciones:
#   -f <archivo>    Nombre del keystore (sin subdirectorios; default: moonblogger-release.jks)
#   -a <alias>      Alias de la clave (default: moonblogger)
#   -v <días>       Validez en días (default: 10000)
#   -d <directorio> Directorio de salida (default: <repo>/android). Útil para CI o pruebas.
#   -n              No interactivo: genera contraseñas aleatorias sin preguntar.
#   -h              Muestra esta ayuda.
#
# Las contraseñas NUNCA se imprimen en el log. En modo interactivo se piden con
# read -s (sin eco); en modo -n se genera una aleatoria. Se usa la MISMA
# contraseña para el keystore y para la clave: keytool genera PKCS12 por
# defecto y en ese formato la contraseña de store y de clave deben coincidir
# (si difieren, keytool ignora -keypass y la firma fallaría en Gradle).
# Se pasan a keytool por variables de entorno (-storepass:env / -keypass:env)
# para evitar que aparezcan en la línea de comandos (ps), y se escriben
# únicamente en keystore.properties con permisos 600.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuración y opciones
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

KS_FILENAME="moonblogger-release.jks"
KS_ALIAS="moonblogger"
KS_VALIDITY=10000
KS_DIR="$REPO_ROOT/android"
NON_INTERACTIVE=0

usage() {
  # Imprime la cabecera de comentarios del propio script (desde la línea 2
  # hasta la primera línea que no sea comentario).
  awk 'NR > 1 && /^#/ { print substr($0, 3) } NR > 1 && $0 !~ /^#/ { exit }' "${BASH_SOURCE[0]}"
}

while getopts ":f:a:v:d:nh" opt; do
  case "$opt" in
    f) KS_FILENAME="$OPTARG" ;;
    a) KS_ALIAS="$OPTARG" ;;
    v) KS_VALIDITY="$OPTARG" ;;
    d) KS_DIR="$OPTARG" ;;
    n) NON_INTERACTIVE=1 ;;
    h) usage; exit 0 ;;
    \?) echo "Opción desconocida: -$OPTARG" >&2; usage >&2; exit 1 ;;
    :) echo "La opción -$OPTARG requiere un argumento" >&2; usage >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Comprobaciones previas
# ---------------------------------------------------------------------------
if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool no está en el PATH. Instala/activa un JDK (Java 17+)." >&2
  exit 1
fi

if ! [[ "$KS_VALIDITY" =~ ^[0-9]+$ ]] || [ "$KS_VALIDITY" -le 0 ]; then
  echo "ERROR: la validez debe ser un número de días positivo (recibido: $KS_VALIDITY)" >&2
  exit 1
fi

KS_PATH="$KS_DIR/$KS_FILENAME"
PROPS_PATH="$KS_DIR/keystore.properties"

# No sobrescribir un keystore existente: regenerarlo rompería la identidad de
# firma y las actualizaciones ya instaladas (Android exige la misma firma).
if [ -e "$KS_PATH" ]; then
  echo "ERROR: ya existe $KS_PATH" >&2
  echo "       No se regenera a propósito: el keystore es la identidad de firma." >&2
  echo "       Si lo perdiste, revisa tu copia de seguridad (ver CUSTODIA)." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Contraseñas (una sola: store y clave, ver cabecera)
# ---------------------------------------------------------------------------
random_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 24 | tr -d '\n'
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

prompt_password() {
  local pass="" confirm=""
  while :; do
    read -r -s -p "Contraseña del keystore y de la clave ($KS_ALIAS): " pass
    echo >&2
    read -r -s -p "Repite la contraseña: " confirm
    echo >&2
    if [ -z "$pass" ]; then
      echo "La contraseña no puede estar vacía. Inténtalo de nuevo." >&2
      continue
    fi
    if [ "$pass" != "$confirm" ]; then
      echo "Las contraseñas no coinciden. Inténtalo de nuevo." >&2
      continue
    fi
    echo "$pass"
    return 0
  done
}

if [ "$NON_INTERACTIVE" = "1" ]; then
  KEYSTORE_PASS="$(random_password)"
  echo "Modo no interactivo (-n): contraseña aleatoria generada (solo se escribe en keystore.properties)."
else
  echo "Introduce la contraseña (no se muestra al escribir)."
  KEYSTORE_PASS="$(prompt_password)"
fi

# ---------------------------------------------------------------------------
# Generar keystore (sin contraseñas en la línea de comandos)
# ---------------------------------------------------------------------------
mkdir -p "$KS_DIR"

MOON_KS_KEYSTORE_PASS="$KEYSTORE_PASS" \
  keytool -genkeypair \
    -keystore "$KS_PATH" \
    -storepass:env MOON_KS_KEYSTORE_PASS \
    -keypass:env MOON_KS_KEYSTORE_PASS \
    -alias "$KS_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$KS_VALIDITY" \
    -dname "CN=MoonBlogger Android, OU=Android, O=MoonBlogger, L=Desconocido, ST=Desconocido, C=ES"

chmod 600 "$KS_PATH"

# ---------------------------------------------------------------------------
# Escribir keystore.properties (ruta relativa a android/ para local y CI)
# ---------------------------------------------------------------------------
cat > "$PROPS_PATH" <<EOF
# MoonBlogger — keystore de firma del release. NO versionar.
# Generado por scripts/create-keystore.sh el $(date -u +%Y-%m-%dT%H:%M:%SZ).
# storeFile es relativo a android/ (rootProject.file() en build.gradle.kts).
storeFile=$KS_FILENAME
storePassword=$KEYSTORE_PASS
keyAlias=$KS_ALIAS
keyPassword=$KEYSTORE_PASS
EOF
chmod 600 "$PROPS_PATH"

echo
echo "=== Keystore de firma creado ==="
echo "  Keystore : $KS_PATH"
echo "  Alias    : $KS_ALIAS"
echo "  Validez  : $KS_VALIDITY días"
echo "  Config   : $PROPS_PATH (permisos 600)"
echo
echo "CUSTODIA (importante):"
echo "  - Este keystore es la IDENTIDAD de firma de MoonBlogger."
echo "  - Si se pierde, o se olvidan las contraseñas, NO se podrá instalar"
echo "    una actualización sobre la misma app (Android exige la misma firma)."
echo "  - La contraseña no se puede recuperar: no existe 'reset'."
echo "  - Guarda una copia offline (disco externo / gestor de contraseñas)"
echo "    junto con storePassword y keyPassword."
echo "  - NO lo subas a git: .gitignore excluye keystore.properties y *.jks."
echo
echo "Siguiente paso: cd android && ./gradlew assembleRelease"
