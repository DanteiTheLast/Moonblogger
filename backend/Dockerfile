# MoonBlogger backend - imagen de produccion para Koyeb.
# En Koyeb configura el working directory como "backend" (build context),
# con Dockerfile "Dockerfile" relativo a ese contexto.

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Dependencias primero para aprovechar la cache de capas de Docker.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Recoge los estaticos para que WhiteNoise pueda servirlos.
# Si DJANGO_DEBUG=false se define como env var de build, tambien hacen falta
# DJANGO_SECRET_KEY y DJANGO_ALLOWED_HOSTS en el build.
RUN python manage.py collectstatic --noinput

# Koyeb inyecta PORT; gunicorn escucha en 0.0.0.0:$PORT (8000 por defecto).
EXPOSE 8000

# Sin HEALTHCHECK en la imagen: python:3.13-slim no trae curl y el health
# check se configura en el dashboard de Koyeb contra /api/v1/health/.
CMD ["sh", "-c", "gunicorn moonblogger.wsgi:application --bind 0.0.0.0:${PORT:-8000} --workers 2 --threads 4 --timeout 120"]
