from django.contrib.auth import get_user_model
import uuid

from django.db import models
from django.db.models import Q
from django.utils import timezone
from django.utils.text import slugify
from django.core.validators import validate_ipv46_address

User = get_user_model()


class Post(models.Model):
    class Status(models.TextChoices):
        DRAFT = "draft", "Borrador"
        PUBLISHED = "published", "Publicada"

    author = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name="posts",
    )
    title = models.CharField(max_length=200)
    slug = models.SlugField(max_length=200, unique=True)
    content = models.TextField()
    status = models.CharField(
        max_length=20,
        choices=Status.choices,
        default=Status.DRAFT,
        db_index=True,
    )
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
    updated_at = models.DateTimeField(auto_now=True)
    published_at = models.DateTimeField(null=True, blank=True, db_index=True)
    carousel_transition = models.CharField(
        max_length=10,
        choices=(
            ("slide", "Slide"),
            ("fade", "Fade"),
            ("bubble", "Bubble"),
            ("none", "None"),
        ),
        default="slide",
    )

    class Meta:
        ordering = ["-created_at", "-id"]

    def __str__(self):
        return self.title

    def save(self, *args, **kwargs):
        if not self.slug:
            self.slug = self._generate_unique_slug()
        if self.status == self.Status.PUBLISHED and self.published_at is None:
            self.published_at = timezone.now()
        elif self.status == self.Status.DRAFT:
            self.published_at = None
        super().save(*args, **kwargs)

    def _generate_unique_slug(self):
        base = slugify(self.title) or "post"
        slug = base
        counter = 2
        while Post.objects.filter(slug=slug).exists():
            slug = f"{base}-{counter}"
            counter += 1
        return slug


class PublicVisit(models.Model):
    path = models.CharField(max_length=220)
    ip_address = models.GenericIPAddressField(validators=[validate_ipv46_address])
    visit_date = models.DateField()
    user_agent = models.CharField(max_length=512, blank=True)
    first_seen_at = models.DateTimeField()
    last_seen_at = models.DateTimeField()
    hit_count = models.PositiveIntegerField(default=1)

    class Meta:
        constraints = [models.UniqueConstraint(fields=("path", "ip_address", "visit_date"), name="public_visit_unique_day")]
        indexes = [
            models.Index(fields=("visit_date", "path")),
            models.Index(fields=("ip_address", "visit_date")),
            models.Index(fields=("last_seen_at",)),
        ]
        ordering = ["-last_seen_at"]


class PostMedia(models.Model):
    class Kind(models.TextChoices):
        IMAGE = "image", "Imagen"
        VIDEO = "video", "Vídeo"

    class State(models.TextChoices):
        PENDING = "pending", "Pendiente"
        READY = "ready", "Lista"
        FAILED = "failed", "Fallida"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    post = models.ForeignKey(Post, on_delete=models.CASCADE, related_name="media")
    kind = models.CharField(max_length=10, choices=Kind.choices)
    state = models.CharField(max_length=10, choices=State.choices, default=State.PENDING)
    position = models.PositiveSmallIntegerField(null=True, blank=True)
    is_cover = models.BooleanField(default=False)
    private_object_key = models.CharField(max_length=500, null=True, blank=True)
    public_object_key = models.CharField(max_length=500, null=True, blank=True)
    mime_type = models.CharField(max_length=100)
    size_bytes = models.PositiveBigIntegerField()
    width = models.PositiveIntegerField(null=True, blank=True)
    height = models.PositiveIntegerField(null=True, blank=True)
    duration_seconds = models.PositiveIntegerField(null=True, blank=True)
    alt_text = models.CharField(max_length=500, blank=True)
    caption = models.CharField(max_length=1000, blank=True)
    private_poster_key = models.CharField(max_length=500, null=True, blank=True)
    public_poster_key = models.CharField(max_length=500, null=True, blank=True)
    poster_mime_type = models.CharField(max_length=100, null=True, blank=True)
    poster_size_bytes = models.PositiveBigIntegerField(null=True, blank=True)
    upload_expires_at = models.DateTimeField(null=True, blank=True, db_index=True)
    ready_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["position", "created_at"]
        constraints = [
            models.UniqueConstraint(
                fields=("post", "position"),
                condition=Q(position__isnull=False),
                name="post_media_unique_active_position",
            ),
            models.UniqueConstraint(
                fields=("post",),
                condition=Q(is_cover=True),
                name="post_media_one_cover",
            ),
            models.CheckConstraint(
                condition=Q(is_cover=False) | Q(position__isnull=False),
                name="post_media_cover_requires_position",
            ),
        ]
        indexes = [
            models.Index(fields=("post", "state", "position")),
            models.Index(fields=("state", "upload_expires_at")),
        ]


class StorageDeletionTask(models.Model):
    """Durable outbox for best-effort storage deletion."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    bucket = models.CharField(max_length=255)
    object_key = models.CharField(max_length=500)
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True, db_index=True)
    attempts = models.PositiveIntegerField(default=0)
    last_error = models.TextField(blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=("bucket", "object_key"), name="storage_delete_unique_object")
        ]
        indexes = [models.Index(fields=("completed_at", "created_at"))]
