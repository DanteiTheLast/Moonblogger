import hashlib
import os
import threading
import urllib.parse
import urllib.request

from django.db import transaction
from django.dispatch import receiver
from django.db.models.signals import post_delete, post_save, pre_delete, pre_save, m2m_changed

from .models import Post, PostMedia, Tag
from .services import enqueue_media_deletions


def _send_webhook(old_status, new_status):
    """Fire-and-forget webhook for published status transitions.

    Uses urllib.request (stdlib, no extra deps). Runs in a daemon thread
    with a 3-second timeout and silent error handling.

    Only fires when status transitions to/from published.
    """
    revalidate_url = os.environ.get("WEB_REVALIDATE_URL")
    revalidate_secret = os.environ.get("WEB_REVALIDATE_SECRET")

    # Guard: no config -> no-op (dev/tests)
    if not revalidate_url or not revalidate_secret:
        return

    # Only fire on transitions to/from published
    to_published = new_status == Post.Status.PUBLISHED
    from_published = old_status == Post.Status.PUBLISHED
    if not (to_published or from_published):
        return

    payload = {
        "old_status": old_status,
        "new_status": new_status,
    }

    data = urllib.parse.urlencode(payload).encode("utf-8")

    req = urllib.request.Request(
        revalidate_url,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Revalidate-Secret": hashlib.sha256(
                revalidate_secret.encode("utf-8")
            ).hexdigest(),
        },
    )

    def _run():
        try:
            urllib.request.urlopen(req, timeout=3)
        except Exception:
            pass

    thread = threading.Thread(target=_run, daemon=True)
    thread.start()


# Capture old status before it gets overwritten by save()
@receiver(pre_save, sender=Post)
def _on_post_save_pre(sender, instance, **kwargs):
    """Capture old_status before the Post is saved.

    Stores the previous status on the instance so that post_save can
    reference it without an extra database query. On first creation
    (when the instance has no pk yet), old_status is set to DRAFT
    since new posts start as drafts by default.
    """
    if instance.pk:
        # Existing post being updated: fetch the old status from DB
        try:
            old = Post.objects.get(pk=instance.pk)
            instance._old_status = old.status
        except Post.DoesNotExist:
            instance._old_status = Post.Status.DRAFT
    else:
        # New post: old_status is DRAFT (default)
        instance._old_status = Post.Status.DRAFT


@receiver(post_save, sender=Post)
def _on_post_save(sender, instance, created, **kwargs):
    """Post-save signal handler that fires webhook on published status transitions.

    Uses the _old_status captured in pre_save to avoid extra queries.
    Fires a fire-and-forget webhook only when status transitions to/from
    published, using transaction.on_commit() to ensure the DB transaction
    is complete before sending.
    """
    old_status = getattr(instance, "_old_status", Post.Status.DRAFT)
    new_status = instance.status

    # On creation, if the new post is published, it's a transition from DRAFT
    if created:
        # New post with status=published transitions from DRAFT (default)
        # to published
        transaction.on_commit(
            lambda: _send_webhook(old_status, new_status)
        )
    else:
        # Update: old_status was captured in pre_save
        transaction.on_commit(
            lambda: _send_webhook(old_status, new_status)
        )


@receiver(post_delete, sender=Post)
def _on_post_delete(sender, instance, **kwargs):
    """Invalidate public pages after deleting a published post."""
    transaction.on_commit(
        lambda: _send_webhook(instance.status, Post.Status.DRAFT)
    )


@receiver(pre_delete, sender=PostMedia)
def _queue_deleted_media_objects(sender, instance, **kwargs):
    """Persist cleanup work in the same DB transaction as the deletion."""
    enqueue_media_deletions(instance)


@receiver(m2m_changed, sender=Post.tags.through)
def _on_post_tags_changed(sender, instance, action, reverse, pk_set, **kwargs):
    # Reverse clear needs the affected posts captured before the relation disappears.
    if reverse and action == "pre_clear":
        instance._cleared_public_tags = instance.posts.filter(status=Post.Status.PUBLISHED).exists()
    if action not in {"post_add", "post_remove", "post_clear"}:
        return
    if reverse:
        public = (getattr(instance, "_cleared_public_tags", False) if action == "post_clear"
                  else Post.objects.filter(pk__in=pk_set, status=Post.Status.PUBLISHED).exists())
    else:
        public = instance.status == Post.Status.PUBLISHED
    if public:
        transaction.on_commit(lambda: _send_webhook(Post.Status.PUBLISHED, Post.Status.PUBLISHED))


@receiver(pre_save, sender=Tag)
def _capture_public_tag_change(sender, instance, **kwargs):
    instance._public_tag_changed = False
    if instance.pk:
        previous = Tag.objects.filter(pk=instance.pk).values("name", "sort_order", "is_active").first()
        instance._public_tag_changed = bool(previous and any(previous[key] != getattr(instance, key) for key in previous)
                                            and instance.posts.filter(status=Post.Status.PUBLISHED).exists())


@receiver(post_save, sender=Tag)
def _invalidate_public_tag_change(sender, instance, **kwargs):
    if getattr(instance, "_public_tag_changed", False):
        transaction.on_commit(lambda: _send_webhook(Post.Status.PUBLISHED, Post.Status.PUBLISHED))


@receiver(pre_delete, sender=Tag)
def _capture_public_tag_deletion(sender, instance, **kwargs):
    instance._was_public = instance.posts.filter(status=Post.Status.PUBLISHED).exists()


@receiver(post_delete, sender=Tag)
def _invalidate_public_tag_deletion(sender, instance, **kwargs):
    if getattr(instance, "_was_public", False):
        transaction.on_commit(lambda: _send_webhook(Post.Status.PUBLISHED, Post.Status.PUBLISHED))
