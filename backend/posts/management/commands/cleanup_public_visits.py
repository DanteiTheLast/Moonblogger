from datetime import timedelta
from django.conf import settings
from django.core.management.base import BaseCommand
from django.utils import timezone
from posts.models import PublicVisit

class Command(BaseCommand):
    help = "Delete visits beyond the configured retention period."
    def handle(self, *args, **kwargs):
        cutoff = timezone.now().date() - timedelta(days=settings.VISIT_RETENTION_DAYS)
        total = 0
        while True:
            ids = list(PublicVisit.objects.filter(visit_date__lt=cutoff).values_list("pk", flat=True)[:settings.VISIT_CLEANUP_BATCH_SIZE])
            if not ids:
                break
            deleted, _ = PublicVisit.objects.filter(pk__in=ids).delete()
            total += deleted
        self.stdout.write(self.style.SUCCESS(f"Deleted {total} public visits."))
