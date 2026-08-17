from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from posts.models import PostMedia, StorageDeletionTask
from posts.storage import StorageError, StorageObjectNotFound, get_storage


class Command(BaseCommand):
    help = "Elimina intents vencidos y reprocesa la cola persistente de borrado de Storage."

    def handle(self, *args, **options):
        now = timezone.now()
        expired = PostMedia.objects.filter(
            state__in=[PostMedia.State.PENDING, PostMedia.State.FAILED],
            upload_expires_at__lte=now,
        )
        expired_count = expired.count()
        # pre_delete enqueues object cleanup within the same transaction.
        with transaction.atomic():
            expired.delete()

        try:
            storage = get_storage()
        except StorageError as exc:
            self.stdout.write(
                self.style.WARNING(
                    f"Se eliminaron {expired_count} intents vencidos; Storage no está configurado: {exc}"
                )
            )
            return

        completed = 0
        failed = 0
        for task_id in StorageDeletionTask.objects.filter(completed_at__isnull=True).values_list("id", flat=True):
            with transaction.atomic():
                task = StorageDeletionTask.objects.select_for_update().get(pk=task_id)
                if task.completed_at:
                    continue
                try:
                    storage.delete(task.bucket, task.object_key)
                except StorageObjectNotFound:
                    # A missing object is already in the desired deleted state.
                    task.attempts += 1
                    task.last_error = ""
                    task.completed_at = timezone.now()
                    task.save(update_fields=["attempts", "last_error", "completed_at"])
                    completed += 1
                except StorageError as exc:
                    task.attempts += 1
                    task.last_error = str(exc)
                    task.save(update_fields=["attempts", "last_error"])
                    failed += 1
                else:
                    task.attempts += 1
                    task.last_error = ""
                    task.completed_at = timezone.now()
                    task.save(update_fields=["attempts", "last_error", "completed_at"])
                    completed += 1
        self.stdout.write(
            self.style.SUCCESS(
                f"Intents vencidos: {expired_count}; objetos eliminados: {completed}; reintentos fallidos: {failed}."
            )
        )
