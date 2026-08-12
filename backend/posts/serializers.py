from rest_framework import serializers

from .models import Post


class PostSerializer(serializers.ModelSerializer):
    class Meta:
        model = Post
        fields = [
            "id",
            "slug",
            "title",
            "content",
            "status",
            "created_at",
            "updated_at",
            "published_at",
        ]
        read_only_fields = [
            "id",
            "slug",
            "created_at",
            "updated_at",
            "published_at",
        ]

    def validate_title(self, value):
        if not value.strip():
            raise serializers.ValidationError("El título no puede estar vacío.")
        return value.strip()

    def validate_content(self, value):
        if not value.strip():
            raise serializers.ValidationError("El contenido no puede estar vacío.")
        return value.strip()
