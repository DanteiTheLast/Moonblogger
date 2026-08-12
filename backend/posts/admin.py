from django.contrib import admin

from .models import Post


@admin.register(Post)
class PostAdmin(admin.ModelAdmin):
    list_display = ("title", "slug", "status", "author", "published_at", "created_at", "updated_at")
    list_filter = ("status", "author")
    search_fields = ("title", "content", "slug")
    readonly_fields = ("created_at", "updated_at", "published_at")
    list_select_related = ("author",)
