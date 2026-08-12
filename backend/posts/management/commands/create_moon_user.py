import os

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand

User = get_user_model()


class Command(BaseCommand):
    help = "Crea o actualiza el usuario de Moon usando MOON_USERNAME y MOON_PASSWORD de backend/.env."

    def handle(self, *args, **options):
        username = os.environ.get("MOON_USERNAME", "moon")
        password = os.environ.get("MOON_PASSWORD", "")
        if not password:
            self.stdout.write(
                self.style.WARNING("MOON_PASSWORD no está definida; no se modifica el usuario.")
            )
            return
        user, created = User.objects.get_or_create(username=username)
        user.set_password(password)
        user.save()
        action = "creado" if created else "actualizado (contraseña renovada)"
        self.stdout.write(self.style.SUCCESS(f"Usuario '{username}' {action}."))
