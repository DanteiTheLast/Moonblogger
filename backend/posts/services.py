import logging
from dataclasses import dataclass

from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from .models import Post, PostMedia, StorageDeletionTask
from .storage import StorageError, StorageObjectNotFound, get_storage


logger = logging.getLogger(__name__)

# Bound synchronous work performed before a new upload intent.  This is enough
# to make progress on a small backlog without making the request unbounded.
MEDIA_HOUSEKEEPING_BATCH_SIZE = 10


@dataclass(frozen=True)
class MediaHousekeepingResult:
    expired_intents: int = 0
    deleted_objects: int = 0
    failed_deletions: int = 0
    storage_available: bool = True


def run_media_housekeeping(limit=MEDIA_HOUSEKEEPING_BATCH_SIZE):
    """Remove expired non-ready intents and retry a bounded deletion outbox batch.

    Database cleanup is intentionally completed before contacting Storage.  Each
    outbox item remains locked while its deletion is attempted, preserving the
    existing at-most-one concurrent worker semantics without holding a lock for
    the whole batch.
    """
    if limit < 1:
        raise ValueError("limit must be positive")

    now = timezone.now()
    with transaction.atomic():
        expired = list(
            PostMedia.objects.select_for_update()
            .filter(
                state__in=[PostMedia.State.PENDING, PostMedia.State.FAILED],
                upload_expires_at__lte=now,
            )
            .order_by("upload_expires_at", "id")[:limit]
        )
        for media in expired:
            # The pre_delete signal writes durable StorageDeletionTask entries.
            media.delete()

    try:
        storage = get_storage()
    except StorageError:
        # Do not expose configuration or provider details in request logs.
        logger.warning("Media housekeeping skipped Storage deletion tasks: Storage unavailable.")
        return MediaHousekeepingResult(expired_intents=len(expired), storage_available=False)

    completed = 0
    failed = 0
    task_ids = list(
        StorageDeletionTask.objects.filter(completed_at__isnull=True)
        .order_by("created_at", "id")
        .values_list("id", flat=True)[:limit]
    )
    for task_id in task_ids:
        with transaction.atomic():
            try:
                task = StorageDeletionTask.objects.select_for_update().get(pk=task_id)
            except StorageDeletionTask.DoesNotExist:
                continue
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
            except StorageError:
                task.attempts += 1
                # Storage adapters may wrap provider errors. Keep diagnostics
                # useful without risking persistence of provider credentials.
                task.last_error = "Storage no pudo completar la eliminación."
                task.save(update_fields=["attempts", "last_error"])
                failed += 1
                logger.warning("Media housekeeping could not delete a Storage object; task retained for retry.")
            else:
                task.attempts += 1
                task.last_error = ""
                task.completed_at = timezone.now()
                task.save(update_fields=["attempts", "last_error", "completed_at"])
                completed += 1

    return MediaHousekeepingResult(
        expired_intents=len(expired),
        deleted_objects=completed,
        failed_deletions=failed,
    )


class PromotionFailed(StorageError):
    def __init__(self, message, bucket, object_keys):
        super().__init__(message)
        self.bucket = bucket
        self.object_keys = object_keys


def active_media(post):
    return list(
        post.media.filter(
            state=PostMedia.State.READY, position__isnull=False
        ).order_by("position")
    )


def validate_active_layout(media):
    if not media:
        return
    if [item.position for item in media] != list(range(len(media))):
        raise ValidationError({"media": ["Las posiciones activas deben ser contiguas desde 0."]})
    covers = [item for item in media if item.is_cover]
    if len(covers) != 1:
        raise ValidationError({"media": ["El layout debe tener exactamente una portada."]})
    for item in media:
        if item.kind == PostMedia.Kind.VIDEO and not item.private_poster_key:
            raise ValidationError({"media": ["Cada vídeo necesita una imagen de portada."]})


def promote_active_media(media):
    """Promote new active objects and persist their public keys after success."""
    validate_active_layout(media)
    if not media:
        return
    storage = get_storage()
    promoted = []
    try:
        for item in media:
            if item.public_object_key == item.private_object_key:
                continue
            if not item.private_object_key:
                raise ValidationError({"media": ["Un elemento activo no tiene objeto privado."]})
            storage.promote(item.private_object_key)
            promoted.append(item.private_object_key)
            if item.private_poster_key:
                storage.promote(item.private_poster_key)
                promoted.append(item.private_poster_key)
    except StorageError as exc:
        raise PromotionFailed(str(exc), storage.public_bucket, promoted) from exc

    for item in media:
        if item.public_object_key == item.private_object_key:
            continue
        item.public_object_key = item.private_object_key
        item.public_poster_key = item.private_poster_key
        item.save(update_fields=["public_object_key", "public_poster_key", "updated_at"])


def publish_post(post):
    """Copy active private objects first; persist the public state only on success."""
    media = active_media(post)
    promote_active_media(media)
    post.status = Post.Status.PUBLISHED
    post.save()


def enqueue_media_deletions(instance):
    try:
        storage = get_storage()
    except StorageError:
        # Configuration can be restored later; bucket names are still needed.
        from django.conf import settings

        private_bucket = settings.MEDIA_STORAGE_PRIVATE_BUCKET
        public_bucket = settings.MEDIA_STORAGE_PUBLIC_BUCKET
    else:
        private_bucket = storage.private_bucket
        public_bucket = storage.public_bucket

    candidates = (
        (private_bucket, instance.private_object_key),
        (private_bucket, instance.private_poster_key),
        (public_bucket, instance.public_object_key),
        (public_bucket, instance.public_poster_key),
    )
    for bucket, object_key in candidates:
        enqueue_storage_deletion(bucket, object_key)


def enqueue_storage_deletion(bucket, object_key):
    """Deduplicate pending work and reactivate a key reused after republication."""
    if not bucket or not object_key:
        return
    from .models import StorageDeletionTask

    task, _ = StorageDeletionTask.objects.get_or_create(bucket=bucket, object_key=object_key)
    if task.completed_at:
        task.completed_at = None
        task.attempts = 0
        task.last_error = ""
        task.save(update_fields=["completed_at", "attempts", "last_error"])


def queue_public_deletion(object_key):
    """Withdraw an immutable public object through the durable deletion outbox."""
    if not object_key:
        return
    try:
        storage = get_storage()
        bucket = storage.public_bucket
    except StorageError:
        from django.conf import settings

        bucket = settings.MEDIA_STORAGE_PUBLIC_BUCKET
    if bucket:
        enqueue_storage_deletion(bucket, object_key)


def withdraw_public_media(media):
    """Make media non-public in DB and enqueue remote deletion after commit."""
    for item in media:
        queue_public_deletion(item.public_object_key)
        queue_public_deletion(item.public_poster_key)
        if item.public_object_key or item.public_poster_key:
            item.public_object_key = None
            item.public_poster_key = None
            item.save(update_fields=["public_object_key", "public_poster_key", "updated_at"])
