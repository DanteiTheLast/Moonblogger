#!/usr/bin/env bash
#
# e2e.sh — Smoke test E2E del contrato de API de MoonBlogger
#
# Valida contra un backend real en ejecución:
#   - GET  /health/
#   - POST /auth/login/                  (JWT: access + refresh)
#   - POST /auth/refresh/                (rotación: refresh nuevo != anterior)
#   - CRUD autenticado de /posts/
#   - Lectura pública de /public/posts/
#   - Casos negativos (401/400/404)
#
# Dependencias: curl y jq.
#   - Si jq no está instalado, se intenta instalar con sudo (apt-get).
#   - Si no se puede, se usa python3 como parser JSON alternativo
#     (asistente incrustado en $TMP/json_helper.py).
#
# Variables de entorno (con defaults):
#   API_BASE_URL   (default: http://127.0.0.1:8000/api/v1)
#   MOON_USERNAME  (default: leída de backend/.env si existe)
#   MOON_PASSWORD  (default: leída de backend/.env si existe)
#
# Idempotente: títulos únicos por ejecución (timestamp+PID), limpieza de
# posts creados al final y purga de huérfanos de ejecuciones abortadas.
#
# Salida: "PASS <paso>" / "FAIL <paso>: <detalle>" por etapa.
# Exit 0 si todos los pasos pasan; exit 1 si alguno falla.
# Los avisos (WARN) son observaciones no bloqueantes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ENV_FILE="${REPO_ROOT}/backend/.env"

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------
API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:8000/api/v1}"
API_BASE_URL="${API_BASE_URL%/}"

MOON_USERNAME="${MOON_USERNAME:-}"
MOON_PASSWORD="${MOON_PASSWORD:-}"

# Defaults de credenciales desde backend/.env (sin imprimirlas nunca)
if [ -z "$MOON_USERNAME" ] || [ -z "$MOON_PASSWORD" ]; then
  if [ -f "$ENV_FILE" ]; then
    if [ -z "$MOON_USERNAME" ]; then
      MOON_USERNAME="$(grep -E '^MOON_USERNAME=' "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '\r' || true)"
    fi
    if [ -z "$MOON_PASSWORD" ]; then
      MOON_PASSWORD="$(grep -E '^MOON_PASSWORD=' "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '\r' || true)"
    fi
  fi
fi

if [ -z "$MOON_USERNAME" ] || [ -z "$MOON_PASSWORD" ]; then
  echo "ERROR: MOON_USERNAME/MOON_PASSWORD no definidas y no presentes en $ENV_FILE" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Herramientas: jq (preferido) o python3 (fallback documentado)
# ---------------------------------------------------------------------------
JQ_BIN=""
PY_BIN=""
if command -v jq >/dev/null 2>&1; then
  JQ_BIN="$(command -v jq)"
else
  echo "AVISO: jq no está instalado. Intentando instalarlo..."
  if sudo -n apt-get install -y jq >/dev/null 2>&1; then
    JQ_BIN="$(command -v jq || true)"
    if [ -n "$JQ_BIN" ]; then
      echo "jq instalado correctamente."
    fi
  fi
  if [ -z "$JQ_BIN" ]; then
    echo "AVISO: no se pudo instalar jq (sudo requiere contraseña)."
    echo "        Se usará python3 como parser JSON alternativo."
  fi
fi

if [ -z "$JQ_BIN" ]; then
  if command -v python3 >/dev/null 2>&1; then
    PY_BIN="$(command -v python3)"
  else
    echo "ERROR: se requiere jq o python3 para parsear JSON" >&2
    exit 1
  fi
fi

TMP="$(mktemp -d)"
JSON_HELPER="$TMP/json_helper.py"

# Asistente JSON en python3 (usado solo si jq no está disponible).
cat > "$JSON_HELPER" <<'PYEOF'
import json, sys

def traverse(data, path):
    cur = data
    for part in path.lstrip('.').split('.'):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            raise KeyError(part)
    return cur

cmd = sys.argv[1]
with open(sys.argv[2]) as f:
    data = json.load(f)

if cmd == 'get':
    try:
        val = traverse(data, sys.argv[3])
    except KeyError:
        sys.exit(3)
    if val is None:
        print('null')
    elif isinstance(val, (dict, list)):
        print(json.dumps(val))
    else:
        print(val)
elif cmd == 'has':
    try:
        traverse(data, sys.argv[3])
    except KeyError:
        sys.exit(1)
elif cmd == 'contains':
    val = traverse(data, sys.argv[3])
    field = sys.argv[4]
    needle = sys.argv[5]
    if isinstance(val, list):
        for item in val:
            if isinstance(item, dict) and str(item.get(field, '')) == needle:
                sys.exit(0)
    sys.exit(1)
elif cmd == 'filter_prefix':
    val = traverse(data, sys.argv[3])
    field = sys.argv[4]
    prefix = sys.argv[5]
    if isinstance(val, list):
        for item in val:
            if isinstance(item, dict) and str(item.get(field, '')).startswith(prefix):
                print(item.get('id', ''))
PYEOF

# json_get <archivo> <ruta-jq>  -> imprime el valor (null si es JSON null)
json_get() {
  if [ -n "$JQ_BIN" ]; then
    jq -r "$2" "$1"
  else
    "$PY_BIN" "$JSON_HELPER" get "$1" "$2"
  fi
}

# json_has <archivo> <ruta-jq>  -> 0 si el campo existe, 1 si no
json_has() {
  if [ -n "$JQ_BIN" ]; then
    jq -e "$2" "$1" >/dev/null 2>&1
  else
    "$PY_BIN" "$JSON_HELPER" has "$1" "$2"
  fi
}

# json_contains <archivo> <ruta-lista> <campo> <valor> -> 0 si algún item tiene campo==valor
json_contains() {
  if [ -n "$JQ_BIN" ]; then
    jq -e --arg needle "$4" "$2 | map(.$3 | tostring) | index(\$needle) != null" "$1" >/dev/null 2>&1
  else
    "$PY_BIN" "$JSON_HELPER" contains "$1" "$2" "$3" "$4"
  fi
}

# json_escape <texto> -> imprime el texto como cadena JSON entre comillas
json_escape() {
  if [ -n "$PY_BIN" ]; then
    "$PY_BIN" -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"
  else
    jq -n --arg s "$1" '$s'
  fi
}

# req <archivo-salida> <método> <url> [--json <archivo-body>] [--auth <token>]
# Imprime el código HTTP. El cuerpo va a <archivo-salida>.
req() {
  local out="$1" method="$2" url="$3"
  shift 3
  local body="" auth=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --json) body="$2"; shift 2 ;;
      --auth) auth="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  local args=(-sS -o "$out" -w "%{http_code}" -X "$method" "$url")
  if [ -n "$body" ]; then
    args+=(-H "Content-Type: application/json" --data-binary @"$body")
  fi
  if [ -n "$auth" ]; then
    args+=(-H "Authorization: Bearer $auth")
  fi
  curl "${args[@]}"
}

# ---------------------------------------------------------------------------
# Contadores y salida
# ---------------------------------------------------------------------------
declare -i PASS=0
declare -i FAIL=0
declare -a FAILURES=()
declare -a WARNINGS=()

pass() { PASS+=1; echo "  PASS $1"; }
fail() { FAIL+=1; FAILURES+=("$1"); echo "  FAIL $1: $2"; }
warn() { WARNINGS+=("$1: $2"); echo "  WARN $1: $2"; }

section() {
  echo
  echo "================================================================"
  echo "== $1"
  echo "================================================================"
}

# ---------------------------------------------------------------------------
# Estado compartido
# ---------------------------------------------------------------------------
ACCESS_TOKEN=""
REFRESH_TOKEN=""
CLEANUP_IDS_FILE="$TMP/created_ids.txt"
: > "$CLEANUP_IDS_FILE"

record_id() { echo "$1" >> "$CLEANUP_IDS_FILE"; }

cleanup() {
  if [ -n "${ACCESS_TOKEN:-}" ] && [ -s "$CLEANUP_IDS_FILE" ]; then
    echo "  cleanup: eliminando posts de prueba de esta ejecución..."
    local pid code
    while IFS= read -r pid; do
      code="$(curl -sS -o /dev/null -w "%{http_code}" -X DELETE "$API_BASE_URL/posts/$pid/" \
        -H "Authorization: Bearer $ACCESS_TOKEN" 2>/dev/null || echo ERR)"
      echo "    DELETE /posts/$pid/ -> $code"
    done < "$CLEANUP_IDS_FILE"
  elif [ -z "${ACCESS_TOKEN:-}" ]; then
    echo "  cleanup: sin token (login falló antes); no hay nada que eliminar."
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

# Purga huérfanos de ejecuciones E2E anteriores que abortaron (idempotencia).
purge_leftover_e2e_posts() {
  local page="$API_BASE_URL/posts/"
  while [ -n "$page" ] && [ "$page" != "null" ]; do
    local file="$TMP/purge_page.json"
    local code ids pid dcode
    code="$(req "$file" GET "$page" --auth "$ACCESS_TOKEN")"
    if [ "$code" != "200" ]; then
      echo "  purge: GET $page -> $code (se omite)"
      return 0
    fi
    if [ -n "$JQ_BIN" ]; then
      ids="$(jq -r '.results[] | select(.title | startswith("E2E-TEST-")) | .id' "$file" || true)"
    else
      ids="$("$PY_BIN" "$JSON_HELPER" filter_prefix "$file" ".results" "title" "E2E-TEST-" || true)"
    fi
    for pid in $ids; do
      dcode="$(req /dev/null DELETE "$API_BASE_URL/posts/$pid/" --auth "$ACCESS_TOKEN")"
      echo "    purge: DELETE /posts/$pid/ (huérfano E2E) -> $dcode"
    done
    page="$(json_get "$file" '.next')"
  done
}

echo "MoonBlogger E2E smoke test"
echo "API base: $API_BASE_URL"
echo "Usuario : $MOON_USERNAME"

# ---------------------------------------------------------------------------
# A. Health
# ---------------------------------------------------------------------------
section "A. Health check"
code="$(curl -sS -o "$TMP/health.json" -w "%{http_code}" "$API_BASE_URL/health/")"
if [ "$code" = "200" ]; then
  pass "health (GET $API_BASE_URL/health/ -> 200)"
else
  fail "health" "expected 200 got $code"
fi

# ---------------------------------------------------------------------------
# B. Login
# ---------------------------------------------------------------------------
section "B. Login"
printf '{"username": %s, "password": %s}' "$(json_escape "$MOON_USERNAME")" "$(json_escape "$MOON_PASSWORD")" \
  > "$TMP/login_body.json"
code="$(req "$TMP/login.json" POST "$API_BASE_URL/auth/login/" --json "$TMP/login_body.json")"
if [ "$code" = "200" ] && json_has "$TMP/login.json" '.access' && json_has "$TMP/login.json" '.refresh'; then
  ACCESS_TOKEN="$(json_get "$TMP/login.json" '.access')"
  REFRESH_TOKEN="$(json_get "$TMP/login.json" '.refresh')"
  pass "login (POST /auth/login/ -> 200, access y refresh presentes)"
else
  fail "login" "expected 200 con access+refresh, got $code"
  echo "  ABORT: sin token no se puede continuar."
  exit 1
fi

# Purga de huérfanos de ejecuciones anteriores (idempotencia)
section "Limpieza previa (idempotencia)"
purge_leftover_e2e_posts
echo "  purge: OK"

# ---------------------------------------------------------------------------
# C. Refresh con rotación
# ---------------------------------------------------------------------------
section "C. Refresh con rotación"
printf '{"refresh": %s}' "$(json_escape "$REFRESH_TOKEN")" > "$TMP/refresh_body.json"
code="$(req "$TMP/refresh.json" POST "$API_BASE_URL/auth/refresh/" --json "$TMP/refresh_body.json")"
if [ "$code" = "200" ] && json_has "$TMP/refresh.json" '.access' && json_has "$TMP/refresh.json" '.refresh'; then
  NEW_ACCESS="$(json_get "$TMP/refresh.json" '.access')"
  NEW_REFRESH="$(json_get "$TMP/refresh.json" '.refresh')"
  if [ -n "$NEW_ACCESS" ] && [ "$NEW_REFRESH" != "$REFRESH_TOKEN" ]; then
    pass "refresh rotation (200, refresh nuevo distinto del anterior)"
    ACCESS_TOKEN="$NEW_ACCESS"
  else
    fail "refresh rotation" "refresh nuevo igual al anterior o access vacío"
  fi
else
  fail "refresh rotation" "expected 200 con access+refresh, got $code"
fi

# Observacional: docs/api.md afirma que el refresh anterior queda invalidado.
# Eso exige BLACKLIST_AFTER_ROTATION + token_blacklist; sin ello, reusar el
# refresh anterior sigue devolviendo 200. No es bloqueante.
printf '{"refresh": %s}' "$(json_escape "$REFRESH_TOKEN")" > "$TMP/refresh_old_body.json"
old_code="$(req "$TMP/refresh_old.json" POST "$API_BASE_URL/auth/refresh/" --json "$TMP/refresh_old_body.json")"
if [ "$old_code" = "401" ]; then
  pass "refresh invalidation (reuso de refresh anterior -> 401)"
elif [ "$old_code" = "200" ]; then
  warn "refresh invalidation" "el refresh anterior sigue válido (200): docs/api.md afirma que queda invalidado, pero BLACKLIST_AFTER_ROTATION no está activo"
else
  warn "refresh invalidation" "reuso de refresh anterior -> código inesperado $old_code"
fi

# ---------------------------------------------------------------------------
# D. CRUD autenticado + visibilidad pública
# ---------------------------------------------------------------------------
section "D. CRUD autenticado"
TS="$(date +%s)-$$"
TITLE_ONE="E2E-TEST-$TS-publicable"
TITLE_TWO="E2E-TEST-$TS-borrador"

# D1. Crear borrador
printf '{"title": %s, "content": %s, "status": "draft"}' \
  "$(json_escape "$TITLE_ONE")" "$(json_escape "Contenido E2E $TS uno")" > "$TMP/post1_body.json"
code="$(req "$TMP/post1.json" POST "$API_BASE_URL/posts/" --json "$TMP/post1_body.json" --auth "$ACCESS_TOKEN")"
if [ "$code" = "201" ] && json_has "$TMP/post1.json" '.id' && json_has "$TMP/post1.json" '.slug'; then
  POST1_ID="$(json_get "$TMP/post1.json" '.id')"
  POST1_SLUG="$(json_get "$TMP/post1.json" '.slug')"
  record_id "$POST1_ID"
  pass "create draft (POST /posts/ -> 201, id=$POST1_ID slug=$POST1_SLUG)"
else
  fail "create draft" "expected 201 con id+slug, got $code"
fi

# D2. El listado autenticado contiene el post
code="$(req "$TMP/posts_list.json" GET "$API_BASE_URL/posts/" --auth "$ACCESS_TOKEN")"
if [ "$code" = "200" ] && json_contains "$TMP/posts_list.json" ".results" "id" "$POST1_ID"; then
  pass "list contains (GET /posts/ -> 200, post en results)"
else
  fail "list contains" "expected 200 con post en results, got $code"
fi

# D3. Publicar
printf '{"status": "published"}' > "$TMP/patch_publish.json"
code="$(req "$TMP/post1_published.json" PATCH "$API_BASE_URL/posts/$POST1_ID/" \
  --json "$TMP/patch_publish.json" --auth "$ACCESS_TOKEN")"
if [ "$code" = "200" ] && [ "$(json_get "$TMP/post1_published.json" '.status')" = "published" ]; then
  pass "publish (PATCH /posts/$POST1_ID/ -> 200, status=published)"
else
  fail "publish" "expected 200 con status=published, got $code"
fi

# D4. El listado público contiene el post publicado
code="$(req "$TMP/public_list.json" GET "$API_BASE_URL/public/posts/")"
if [ "$code" = "200" ] && json_contains "$TMP/public_list.json" ".results" "slug" "$POST1_SLUG"; then
  pass "public list contains (GET /public/posts/ -> 200, post publicado)"
else
  fail "public list contains" "expected 200 con post publicado en results, got $code"
fi

# D5. Lectura pública por slug
code="$(req "$TMP/public_post1.json" GET "$API_BASE_URL/public/posts/$POST1_SLUG/")"
if [ "$code" = "200" ]; then
  pass "public get published (GET /public/posts/$POST1_SLUG/ -> 200)"
else
  fail "public get published" "expected 200 got $code"
fi

# D6. Segundo post que permanece en borrador: público -> 404
printf '{"title": %s, "content": %s, "status": "draft"}' \
  "$(json_escape "$TITLE_TWO")" "$(json_escape "Contenido E2E $TS dos")" > "$TMP/post2_body.json"
code="$(req "$TMP/post2.json" POST "$API_BASE_URL/posts/" --json "$TMP/post2_body.json" --auth "$ACCESS_TOKEN")"
if [ "$code" = "201" ] && json_has "$TMP/post2.json" '.id' && json_has "$TMP/post2.json" '.slug'; then
  POST2_ID="$(json_get "$TMP/post2.json" '.id')"
  POST2_SLUG="$(json_get "$TMP/post2.json" '.slug')"
  record_id "$POST2_ID"
  pass "create draft2 (POST /posts/ -> 201, id=$POST2_ID slug=$POST2_SLUG)"
else
  POST2_ID=""
  POST2_SLUG=""
  fail "create draft2" "expected 201 con id+slug, got $code"
fi

code="$(req "$TMP/public_draft.json" GET "$API_BASE_URL/public/posts/$POST2_SLUG/")"
if [ "$code" = "404" ]; then
  pass "public get draft (GET /public/posts/$POST2_SLUG/ -> 404)"
else
  fail "public get draft" "expected 404 got $code"
fi

# D7. Slug inexistente -> 404
code="$(req "$TMP/public_missing.json" GET "$API_BASE_URL/public/posts/e2e-no-existe-$TS/")"
if [ "$code" = "404" ]; then
  pass "public get missing (GET /public/posts/e2e-no-existe-$TS/ -> 404)"
else
  fail "public get missing" "expected 404 got $code"
fi

# D8. DELETE -> 204 y GET -> 404
code="$(req /dev/null DELETE "$API_BASE_URL/posts/$POST1_ID/" --auth "$ACCESS_TOKEN")"
if [ "$code" = "204" ]; then
  pass "delete (DELETE /posts/$POST1_ID/ -> 204)"
else
  fail "delete" "expected 204 got $code"
fi

code="$(req "$TMP/deleted_get.json" GET "$API_BASE_URL/posts/$POST1_ID/" --auth "$ACCESS_TOKEN")"
if [ "$code" = "404" ]; then
  pass "get deleted (GET /posts/$POST1_ID/ -> 404)"
else
  fail "get deleted" "expected 404 got $code"
fi

# Eliminar explícitamente el segundo post (el trap lo re-intenta si algo falla)
if [ -n "$POST2_ID" ]; then
  code="$(req /dev/null DELETE "$API_BASE_URL/posts/$POST2_ID/" --auth "$ACCESS_TOKEN")"
  if [ "$code" = "204" ]; then
    pass "delete draft2 (DELETE /posts/$POST2_ID/ -> 204)"
  else
    fail "delete draft2" "expected 204 got $code"
  fi
fi

# ---------------------------------------------------------------------------
# E. Negativos
# ---------------------------------------------------------------------------
section "E. Negativos"

# E1. Sin token -> 401
code="$(curl -sS -o "$TMP/noauth.json" -w "%{http_code}" "$API_BASE_URL/posts/")"
if [ "$code" = "401" ]; then
  pass "posts sin token (GET /posts/ -> 401)"
else
  fail "posts sin token" "expected 401 got $code"
fi

# E2. Título vacío tras strip -> 400
printf '{"title": "   ", "content": "contenido", "status": "draft"}' > "$TMP/bad_title_body.json"
code="$(req "$TMP/bad_title.json" POST "$API_BASE_URL/posts/" --json "$TMP/bad_title_body.json" --auth "$ACCESS_TOKEN")"
if [ "$code" = "400" ]; then
  pass "post sin título (title vacío -> 400)"
else
  fail "post sin título" "expected 400 got $code"
fi

# E3. Login con contraseña incorrecta -> 401
printf '{"username": %s, "password": %s}' \
  "$(json_escape "$MOON_USERNAME")" "$(json_escape "password-incorrecta-e2e")" > "$TMP/bad_login_body.json"
code="$(req "$TMP/bad_login.json" POST "$API_BASE_URL/auth/login/" --json "$TMP/bad_login_body.json")"
if [ "$code" = "401" ]; then
  pass "login contraseña incorrecta (POST /auth/login/ -> 401)"
else
  fail "login contraseña incorrecta" "expected 401 got $code"
fi

# ---------------------------------------------------------------------------
# Resumen
# ---------------------------------------------------------------------------
section "Resumen"
echo "PASS: $PASS | FAIL: $FAIL"
if [ ${#WARNINGS[@]} -gt 0 ]; then
  echo "WARN (observaciones no bloqueantes):"
  for w in "${WARNINGS[@]}"; do
    echo "  - $w"
  done
fi
if [ ${#FAILURES[@]} -gt 0 ]; then
  echo "Pasos fallidos:"
  for f in "${FAILURES[@]}"; do
    echo "  - $f"
  done
  exit 1
fi
echo "E2E OK"
exit 0
