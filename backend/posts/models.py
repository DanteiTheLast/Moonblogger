from django.contrib.auth import get_user_model
from django.db import models
from django.utils import timezone
from django.utils.text import slugify

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
