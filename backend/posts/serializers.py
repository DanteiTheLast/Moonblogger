from rest_framework import serializers

from django.conf import settings

from .models import Post, PostMedia


def _public_url(object_key):
    if not object_key or not settings.MEDIA_STORAGE_URL or not settings.MEDIA_STORAGE_PUBLIC_BUCKET:
        return None
    from urllib.parse import quote

    return (
        f"{settings.MEDIA_STORAGE_URL}/storage/v1/object/public/"
        f"{quote(settings.MEDIA_STORAGE_PUBLIC_BUCKET, safe='')}/{quote(object_key, safe='/')}"
    )


class PrivatePostMediaSerializer(serializers.ModelSerializer):
    class Meta:
        model = PostMedia
        fields = [
            "id", "kind", "state", "position", "is_cover", "mime_type", "size_bytes",
            "width", "height", "duration_seconds", "alt_text", "caption",
            "poster_mime_type", "poster_size_bytes",
            "upload_expires_at", "ready_at",
        ]
        read_only_fields = fields


class PublicPostMediaSerializer(serializers.ModelSerializer):
    url = serializers.SerializerMethodField()
    poster_url = serializers.SerializerMethodField()

    class Meta:
        model = PostMedia
        fields = [
            "id", "kind", "position", "is_cover", "mime_type", "width", "height",
            "duration_seconds", "alt_text", "caption", "url", "poster_url",
        ]

    def get_url(self, obj):
        return _public_url(obj.public_object_key)

    def get_poster_url(self, obj):
        return _public_url(obj.public_poster_key)


class PostSerializer(serializers.ModelSerializer):
    media = serializers.SerializerMethodField(read_only=True)
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
            "carousel_transition",
            "media",
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

    def get_media(self, obj):
        return PrivatePostMediaSerializer(obj.media.all().order_by("position", "created_at"), many=True).data


class PublicPostSerializer(serializers.ModelSerializer):
    media_count = serializers.SerializerMethodField()
    cover = serializers.SerializerMethodField()
    media = serializers.SerializerMethodField()

    class Meta:
        model = Post
        fields = [
            "id", "slug", "title", "content", "status", "created_at", "updated_at", "published_at",
            "carousel_transition", "cover", "media_count", "media",
        ]

    def _media(self, obj):
        return [
            item for item in obj.media.all()
            if item.state == PostMedia.State.READY and item.position is not None
        ]

    def get_media_count(self, obj):
        return len(self._media(obj))

    def get_cover(self, obj):
        cover = next((item for item in self._media(obj) if item.is_cover), None)
        return PublicPostMediaSerializer(cover).data if cover else None

    def get_media(self, obj):
        return PublicPostMediaSerializer(self._media(obj), many=True).data


class PublicPostListSerializer(PublicPostSerializer):
    class Meta(PublicPostSerializer.Meta):
        fields = [field for field in PublicPostSerializer.Meta.fields if field != "media"]


class UploadIntentSerializer(serializers.Serializer):
    kind = serializers.ChoiceField(choices=PostMedia.Kind.choices)
    mime_type = serializers.CharField(max_length=100)
    size_bytes = serializers.IntegerField(min_value=1)
    width = serializers.IntegerField(min_value=1, required=False)
    height = serializers.IntegerField(min_value=1, required=False)
    duration_seconds = serializers.IntegerField(min_value=1, required=False)
    poster_mime_type = serializers.CharField(max_length=100, required=False)
    poster_size_bytes = serializers.IntegerField(min_value=1, required=False)

    def validate(self, attrs):
        kind = attrs["kind"]
        mime_type = attrs["mime_type"].lower()
        attrs["mime_type"] = mime_type
        if kind == PostMedia.Kind.IMAGE:
            if mime_type not in {"image/jpeg", "image/png", "image/webp"}:
                raise serializers.ValidationError({"mime_type": "Solo se admiten JPEG, PNG o WebP."})
            if attrs["size_bytes"] > settings.MEDIA_MAX_IMAGE_BYTES:
                raise serializers.ValidationError({"size_bytes": "La imagen supera el límite permitido."})
            if "duration_seconds" in attrs:
                raise serializers.ValidationError({"duration_seconds": "Una imagen no tiene duración."})
        else:
            if mime_type != "video/mp4":
                raise serializers.ValidationError({"mime_type": "Solo se admite vídeo MP4."})
            if attrs["size_bytes"] > settings.MEDIA_MAX_VIDEO_BYTES:
                raise serializers.ValidationError({"size_bytes": "El vídeo supera el límite permitido."})
            if attrs.get("duration_seconds", 0) > settings.MEDIA_MAX_VIDEO_DURATION_SECONDS:
                raise serializers.ValidationError({"duration_seconds": "El vídeo supera los 120 segundos."})
            if not attrs.get("duration_seconds"):
                raise serializers.ValidationError({"duration_seconds": "La duración declarada es obligatoria para vídeo."})
            poster_mime = attrs.get("poster_mime_type", "").lower()
            if poster_mime not in {"image/jpeg", "image/png", "image/webp"}:
                raise serializers.ValidationError({"poster_mime_type": "El póster debe ser JPEG, PNG o WebP."})
            attrs["poster_mime_type"] = poster_mime
            if not attrs.get("poster_size_bytes") or attrs["poster_size_bytes"] > settings.MEDIA_MAX_IMAGE_BYTES:
                raise serializers.ValidationError({"poster_size_bytes": "El póster supera el límite permitido."})
        return attrs


class CompleteMediaSerializer(serializers.Serializer):
    media_id = serializers.UUIDField()


class MediaMetadataSerializer(serializers.ModelSerializer):
    class Meta:
        model = PostMedia
        fields = ["alt_text", "caption"]


class LayoutItemSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    position = serializers.IntegerField(min_value=0)
    is_cover = serializers.BooleanField()


class MediaLayoutSerializer(serializers.Serializer):
    items = LayoutItemSerializer(many=True)
    carousel_transition = serializers.ChoiceField(
        choices=["slide", "fade", "bubble", "none"], required=False
    )

    def validate_items(self, items):
        ids = [item["id"] for item in items]
        if len(ids) != len(set(ids)):
            raise serializers.ValidationError("Un elemento no puede aparecer dos veces.")
        if sorted(item["position"] for item in items) != list(range(len(items))):
            raise serializers.ValidationError("Las posiciones deben ser contiguas desde 0.")
        if items and sum(item["is_cover"] for item in items) != 1:
            raise serializers.ValidationError("Debe indicar exactamente una portada.")
        return items
