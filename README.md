# MoonBlogger

Blog personal de Moon: una app Android para escribir publicaciones y un sitio
web público y estático para leerlas.

```
Android (Kotlin) ──┐
                   ├── HTTPS / REST ──► Django REST API ──► PostgreSQL
Web (Next.js SSG) ─┘
```

## Componentes

| Carpeta | Componente | Stack | Rol |
|---|---|---|---|
| `android/` | Cliente Android | Kotlin + Jetpack Compose + Retrofit | Login y CRUD de publicaciones (único cliente con escritura) |
| `backend/` | API | Django + Django REST Framework | Autenticación, lógica de negocio y acceso a datos |
| `web/` | Sitio web público | Next.js + TypeScript (SSG, 100% estático) | Listado y detalle de publicaciones publicadas |
| `docs/` | Documentación | Markdown | Arquitectura, API, base de datos, decisiones y despliegue |

- **API REST**: único contrato entre componentes; ningún cliente accede
  directamente a PostgreSQL.
- **Autenticación**: JWT (SimpleJWT) para la app Android; la web es pública y
  de solo lectura.
- **Estado de publicación**: `draft` / `published`; la web solo muestra
  publicaciones `published`.

## Requisitos

- Python 3.12/3.13 + Docker (backend y base de datos local).
- Node 22 (web).
- JDK 17+ y Android SDK (app Android).

## Arranque local

```bash
# 1) Base de datos PostgreSQL (docker-compose)
docker compose up -d

# 2) Backend
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python manage.py migrate
python manage.py create_moon_user
python manage.py runserver        # http://127.0.0.1:8000/api/v1/

# 3) Web (requiere la API corriendo, se consulta en build)
cd web
npm install
npm run dev                        # http://localhost:3000
```

La app Android apunta por defecto a `http://10.0.2.2:8000/` (emulador → host).
Para un dispositivo físico, configura la IP del equipo en
`android/local.properties` (ver `android/README.md`).

## Documentación

- [Contexto](docs/context.md)
- [Arquitectura](docs/architecture.md)
- [Contrato de API](docs/api.md)
- [Base de datos](docs/database.md)
- [Decisiones técnicas](docs/decisions.md)
- [Despliegue y operación](docs/deployment.md)

## Despliegue (producción)

Plan gratuito: API en Koyeb Free, PostgreSQL en Supabase Free, web estática en
Vercel Hobby y APK release firmado para instalación directa. Guía completa:
[`docs/deployment.md`](docs/deployment.md).

## Tests

```bash
cd backend && .venv/bin/python manage.py test posts   # API
cd web && npm test && npm run lint                     # Web
cd android && ./gradlew :app:testDebugUnitTest        # Android (JVM)
```
