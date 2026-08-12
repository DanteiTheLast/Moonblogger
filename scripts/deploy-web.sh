#!/usr/bin/env bash
#
# deploy-web.sh — Dispara el "rebuild al publicar" de la web en Vercel
#
# La web de MoonBlogger es 100% estática (SSG con `output: 'export'`). Para
# actualizar la web tras publicar/editar contenido en la API, Vercel debe
# reconstruir el proyecto y generar de nuevo la carpeta `out/` con el
# contenido fresco. Este script dispara ese rebuild mediante el Vercel Deploy
# Hook de producción.
#
# Cómo se crea el hook: Vercel → Project (web) → Settings → Git → Deploy Hooks
# → Create Hook (branch principal). Se obtiene una URL tipo:
#   https://api.vercel.com/v1/integrations/deploy/<id>/<token>
#
# Variables de entorno (obligatoria):
#   VERCEL_DEPLOY_HOOK   URL completa del Deploy Hook de producción.
#
# Dependencias: curl.
#
# Salida: imprime la respuesta del hook (JSON de Vercel).
# Exit 0 si Vercel acepta el despliegue; exit 1 en caso de error.

set -euo pipefail

# ---------------------------------------------------------------------------
# Validación de la variable de entorno
# ---------------------------------------------------------------------------
if [ -z "${VERCEL_DEPLOY_HOOK:-}" ]; then
  echo "ERROR: falta la variable de entorno VERCEL_DEPLOY_HOOK." >&2
  echo "       Es la URL del Deploy Hook de Vercel (Settings → Git → Deploy Hooks)." >&2
  echo "       No se hardcodea en el repo: defínela en tu entorno (CI, shell, etc.)." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Disparo del Deploy Hook
# ---------------------------------------------------------------------------
echo "Disparando Vercel Deploy Hook (rebuild de la web)..."
RESPONSE="$(curl --fail --silent --show-error -X POST "${VERCEL_DEPLOY_HOOK}")"

echo "Respuesta del hook:"
echo "${RESPONSE}"
