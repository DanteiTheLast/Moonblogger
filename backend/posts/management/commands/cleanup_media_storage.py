from django.core.management.base import BaseCommand

from posts.services import MEDIA_HOUSEKEEPING_BATCH_SIZE, run_media_housekeeping


class Command(BaseCommand):
    help = "Elimina intents vencidos y reprocesa la cola persistente de borrado de Storage."

    def add_arguments(self, parser):
        parser.add_argument(
            "--limit",
            type=int,
            default=MEDIA_HOUSEKEEPING_BATCH_SIZE,
            help="Máximo de intents vencidos y de tareas de Storage a procesar (default: 10).",
        )

    def handle(self, *args, **options):
        try:
            result = run_media_housekeeping(limit=options["limit"])
        except ValueError as exc:
            raise ValueError("--limit debe ser un entero positivo.") from exc

        message = (
            f"Intents vencidos: {result.expired_intents}; objetos eliminados: "
            f"{result.deleted_objects}; reintentos fallidos: {result.failed_deletions}."
        )
        if not result.storage_available:
            self.stdout.write(self.style.WARNING(f"{message} Storage no disponible; tareas conservadas."))
            return
        self.stdout.write(
            self.style.SUCCESS(
                message
            )
        )
