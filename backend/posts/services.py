from rest_framework.exceptions import ValidationError

from .models import Post, PostMedia
from .storage import StorageError, get_storage


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
