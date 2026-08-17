import os
from datetime import timedelta
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent

load_dotenv(BASE_DIR / ".env")


def env_bool(name, default=False):
    return os.environ.get(name, str(default)).lower() in ("1", "true", "yes", "on")


def env_list(name, default=""):
    return [item.strip() for item in os.environ.get(name, default).split(",") if item.strip()]


SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "dev-only-insecure-key")

DEBUG = env_bool("DJANGO_DEBUG", True)

ALLOWED_HOSTS = env_list("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1")

# Render inyecta RENDER="true" y RENDER_EXTERNAL_HOSTNAME (hostname onrender.com).
# Render no interpola variables de entorno, así que el host público se añade
# aquí en código. DJANGO_ALLOWED_HOSTS sigue teniendo prioridad (p. ej. para
# un dominio personalizado futuro).
if env_bool("RENDER"):
    render_host = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "").strip()
    if not render_host:
        render_host = (
            os.environ.get("RENDER_EXTERNAL_URL", "").split("://")[-1].split("/")[0].strip()
        )
    if render_host and render_host not in ALLOWED_HOSTS:
        ALLOWED_HOSTS.append(render_host)

# Producción: exigir secretos y hosts explícitos (fail-fast).
if not DEBUG:
    if not os.environ.get("DJANGO_SECRET_KEY"):
        raise ImproperlyConfigured(
            "DJANGO_SECRET_KEY es obligatorio cuando DJANGO_DEBUG=false. "
            "Defínela con un valor seguro en las variables de entorno."
        )
    if not ALLOWED_HOSTS:
        raise ImproperlyConfigured(
            "DJANGO_ALLOWED_HOSTS es obligatorio cuando DJANGO_DEBUG=false. "
            "Defínela con la lista de hosts permitidos (en Render el host público "
            "se añade automáticamente; aquí se añaden dominios adicionales, "
            "p. ej. un dominio personalizado)."
        )

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "corsheaders",
    "rest_framework_simplejwt.token_blacklist",
    "posts",
]

MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    # WhiteNoise sirve los estáticos en producción sin un servidor aparte.
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "moonblogger.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "moonblogger.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": os.environ.get("DB_NAME", "moonblogger"),
        "USER": os.environ.get("DB_USER", "moonblogger"),
        "PASSWORD": os.environ.get("DB_PASSWORD", ""),
        "HOST": os.environ.get("DB_HOST", "localhost"),
        "PORT": os.environ.get("DB_PORT", "5432"),
        "OPTIONS": {
            # Supabase requiere SSL ("require"); localmente se mantiene "disable".
            "sslmode": os.environ.get("DB_SSLMODE", "disable"),
        },
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {
        "NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator",
    },
    {
        "NAME": "django.contrib.auth.password_validation.MinimumLengthValidator",
    },
    {
        "NAME": "django.contrib.auth.password_validation.CommonPasswordValidator",
    },
    {
        "NAME": "django.contrib.auth.password_validation.NumericPasswordValidator",
    },
]

LANGUAGE_CODE = "es"

TIME_ZONE = "UTC"

USE_I18N = True

USE_TZ = True

STATIC_URL = "static/"

# Directorio donde collectstatic agrupa los estáticos (servidos por WhiteNoise).
STATIC_ROOT = BASE_DIR / "staticfiles"

STORAGES = {
    "default": {
        "BACKEND": "django.core.files.storage.FileSystemStorage",
        # Post media already uses the Supabase REST adapter directly, never
        # FileField or Django's default storage. Do not add MEDIA_ROOT/MEDIA_URL.
    },
    "staticfiles": {
        # Compresión + manifest con hashes para servirlos vía WhiteNoise.
        "BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage",
    },
}

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "rest_framework_simplejwt.authentication.JWTAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": [
        "rest_framework.permissions.IsAuthenticated",
    ],
    "DEFAULT_PAGINATION_CLASS": "rest_framework.pagination.PageNumberPagination",
    "PAGE_SIZE": 20,
}

SIMPLE_JWT = {
    "ACCESS_TOKEN_LIFETIME": timedelta(minutes=15),
    "REFRESH_TOKEN_LIFETIME": timedelta(days=7),
    "ROTATE_REFRESH_TOKENS": True,
    "BLACKLIST_AFTER_ROTATION": True,
    "AUTH_HEADER_TYPES": ("Bearer",),
}

CORS_ALLOWED_ORIGINS = env_list("CORS_ALLOWED_ORIGINS")

# Supabase Storage is deliberately kept outside Django's FileField storage:
# clients upload directly with short-lived signed URLs and Django only stores
# metadata/object keys. Leaving these unset keeps post CRUD available; media
# endpoints then return a clear 503 instead of falling back to local files.
MEDIA_STORAGE_URL = os.environ.get(
    "SUPABASE_STORAGE_URL", os.environ.get("SUPABASE_URL", "")
).rstrip("/")
MEDIA_STORAGE_SERVICE_ROLE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
MEDIA_STORAGE_PRIVATE_BUCKET = os.environ.get("SUPABASE_STORAGE_PRIVATE_BUCKET", "")
MEDIA_STORAGE_PUBLIC_BUCKET = os.environ.get("SUPABASE_STORAGE_PUBLIC_BUCKET", "")
# Internal deadline for complete/cleanup. MEDIA_UPLOAD_TTL_SECONDS is retained
# as a backwards-compatible environment-variable alias; it does not revoke a
# signed URL already issued by Supabase.
MEDIA_INTENT_TTL_SECONDS = int(
    os.environ.get("MEDIA_INTENT_TTL_SECONDS", os.environ.get("MEDIA_UPLOAD_TTL_SECONDS", "900"))
)
MEDIA_READ_URL_TTL_SECONDS = int(os.environ.get("MEDIA_READ_URL_TTL_SECONDS", "300"))
MEDIA_MAX_ITEMS_PER_POST = int(os.environ.get("MEDIA_MAX_ITEMS_PER_POST", "10"))
MEDIA_MAX_VIDEOS_PER_POST = int(os.environ.get("MEDIA_MAX_VIDEOS_PER_POST", "2"))
MEDIA_MAX_IMAGE_BYTES = int(os.environ.get("MEDIA_MAX_IMAGE_BYTES", str(8 * 1024 * 1024)))
MEDIA_MAX_VIDEO_BYTES = int(os.environ.get("MEDIA_MAX_VIDEO_BYTES", str(40 * 1024 * 1024)))
MEDIA_MAX_VIDEO_DURATION_SECONDS = int(
    os.environ.get("MEDIA_MAX_VIDEO_DURATION_SECONDS", "120")
)
