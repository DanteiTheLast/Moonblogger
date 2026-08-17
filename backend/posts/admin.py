from django.contrib import admin

from .models import Post, PostMedia, StorageDeletionTask


@admin.register(Post)
class PostAdmin(admin.ModelAdmin):
    list_display = ("title", "slug", "status", "author", "published_at", "created_at", "updated_at")
    list_filter = ("status", "author")
    search_fields = ("title", "content", "slug")
    readonly_fields = ("created_at", "updated_at", "published_at")
    list_select_related = ("author",)


@admin.register(PostMedia)
class PostMediaAdmin(admin.ModelAdmin):
    list_display = ("id", "post", "kind", "state", "position", "is_cover", "ready_at")
    list_filter = ("kind", "state", "is_cover")
    search_fields = ("private_object_key", "public_object_key", "post__title")
    list_select_related = ("post",)
    readonly_fields = ("id", "created_at", "updated_at", "ready_at")


@admin.register(StorageDeletionTask)
class StorageDeletionTaskAdmin(admin.ModelAdmin):
    list_display = ("object_key", "bucket", "attempts", "completed_at", "created_at")
    list_filter = ("bucket", "completed_at")
    search_fields = ("object_key", "last_error")
    readonly_fields = ("id", "created_at", "completed_at", "attempts", "last_error")
