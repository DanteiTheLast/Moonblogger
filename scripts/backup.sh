#!/usr/bin/env bash
#
# backup.sh — Copia de seguridad lógica de la BD de MoonBlogger
#
# Genera un volcado lógico comprimido (pg_dump -Fc) con nombre
# moonblogger_YYYYMMDD_HHMMSS.dump y aplica retención por días.
# Válido para local (docker-compose) y para producción (Supabase).
#
# Dependencia: pg_dump (cliente PostgreSQL) en el PATH.
#
# Variables de entorno (con defaults):
#   DB_HOST                 (default: localhost)
#   DB_PORT                 (default: 5432)
#   DB_NAME                 (default: moonblogger)
#   DB_USER                 (default: moonblogger)
#   DB_PASSWORD             (obligatoria; nunca se imprime)
#   DB_SSLMODE              (opcional; p. ej. "require" para Supabase)
#   BACKUP_DIR              (default: <raíz del repo>/backups)
#   BACKUP_RETENTION_DAYS   (default: 30)
#
# Salida: ruta y tamaño del dump generado.
# Exit 0 en éxito; exit 1 en error (sin dump).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-moonblogger}"
DB_USER="${DB_USER:-moonblogger}"
DB_PASSWORD="${DB_PASSWORD:-}"
DB_SSLMODE="${DB_SSLMODE:-}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"

# ---------------------------------------------------------------------------
# Validaciones
# ---------------------------------------------------------------------------
if ! command -v pg_dump >/dev/null 2>&1; then
  echo "ERROR: no se encontró 'pg_dump' en el PATH." >&2
  echo "       Instala el cliente PostgreSQL (p. ej. postgresql-client)." >&2
  exit 1
fi

if [ -z "$DB_PASSWORD" ]; then
  echo "ERROR: DB_PASSWORD no está definida." >&2
  echo "       Expórtala antes de ejecutar; se pasará a pg_dump vía PGPASSWORD." >&2
  exit 1
fi

case "$BACKUP_RETENTION_DAYS" in
  ''|*[!0-9]*)
    echo "ERROR: BACKUP_RETENTION_DAYS debe ser un número entero (días)." >&2
    exit 1
    ;;
esac

mkdir -p "$BACKUP_DIR"

# ---------------------------------------------------------------------------
# Conexión: PGPASSWORD solo vive para pg_dump y se limpia al salir.
# ---------------------------------------------------------------------------
export PGPASSWORD="$DB_PASSWORD"
trap 'unset PGPASSWORD' EXIT

# ---------------------------------------------------------------------------
# Volcado
# ---------------------------------------------------------------------------
STAMP="$(date +%Y%m%d_%H%M%S)"
DUMP_FILE="$BACKUP_DIR/moonblogger_${STAMP}.dump"

# Argumentos de pg_dump; --sslmode solo si se pide (Supabase: require).
PGDUMP_ARGS=(
  "--host=$DB_HOST"
  "--port=$DB_PORT"
  "--username=$DB_USER"
  "--dbname=$DB_NAME"
  "--format=custom"
)
if [ -n "$DB_SSLMODE" ]; then
  PGDUMP_ARGS+=("--sslmode=$DB_SSLMODE")
fi

echo "Conectando a PostgreSQL en $DB_HOST:$DB_PORT (base '$DB_NAME', usuario '$DB_USER')..."
if ! pg_dump "${PGDUMP_ARGS[@]}" --file "$DUMP_FILE"; then
  echo "ERROR: no se pudo conectar a PostgreSQL en $DB_HOST:$DB_PORT (base '$DB_NAME', usuario '$DB_USER')." >&2
  echo "       Revisa credenciales y red. Para Supabase usa DB_SSLMODE='require'." >&2
  rm -f "$DUMP_FILE"
  exit 1
fi

# ---------------------------------------------------------------------------
# Retención: elimina dumps más antiguos que BACKUP_RETENTION_DAYS días.
# ---------------------------------------------------------------------------
DELETED=0
while IFS= read -r old_file; do
  rm -f "$old_file"
  DELETED=$((DELETED + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'moonblogger_*.dump' -mtime +"$BACKUP_RETENTION_DAYS" -print)

if [ "$DELETED" -gt 0 ]; then
  echo "Retención: se eliminaron $DELETED backup(s) con más de $BACKUP_RETENTION_DAYS días."
fi

# ---------------------------------------------------------------------------
# Resultado
# ---------------------------------------------------------------------------
SIZE="$(du -h "$DUMP_FILE" | cut -f1)"
echo "Backup creado: $DUMP_FILE ($SIZE)"
