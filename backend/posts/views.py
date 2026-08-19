from datetime import timedelta, timezone as dt_timezone

from django.conf import settings
from django.db import transaction, IntegrityError
from django.core.cache import cache
from django.db.models import F
from django.db.models import Prefetch
from django.utils import timezone
from rest_framework import permissions, status, viewsets
from rest_framework.exceptions import APIException, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Post, PostMedia, PublicVisit, PublicVisitEvent
from .analytics import cleanup_expired_visits, sanitize_user_agent, valid_public_path, verified_visitor_ip, utc_now

class PublicVisitView(APIView):
    permission_classes = [permissions.AllowAny]

    def post(self, request):
        if not settings.VISIT_FORWARDING_SECRET:
            return Response(status=status.HTTP_503_SERVICE_UNAVAILABLE)
        if not isinstance(request.data, dict) or set(request.data) != {"path", "event_id"}:
            return Response(status=status.HTTP_400_BAD_REQUEST)
        path = request.data.get("path")
        event_id = request.data.get("event_id")
        if not isinstance(path, str) or not valid_public_path(path) or not isinstance(event_id, str):
            return Response(status=status.HTTP_400_BAD_REQUEST)
        ua = sanitize_user_agent(request.headers.get("X-Visitor-User-Agent", ""))
        timestamp = request.headers.get("X-Visitor-Timestamp", "")
        ip = verified_visitor_ip(request, timestamp, event_id, path, ua)
        if not ip:
            return Response(status=status.HTTP_400_BAD_REQUEST)
        try:
            event_id = str(__import__("uuid").UUID(event_id))
        except (ValueError, AttributeError, TypeError):
            return Response(status=status.HTTP_400_BAD_REQUEST)
        bucket = f"public-visit:{ip}"
        if not cache.add(bucket, 1, timeout=60):
            try:
                if cache.incr(bucket) > 30:
                    return Response(status=status.HTTP_429_TOO_MANY_REQUESTS)
            except ValueError:
                cache.set(bucket, 1, timeout=60)
        now = utc_now()
        try:
            with transaction.atomic():
                visit, created = PublicVisit.objects.get_or_create(path=path, ip_address=ip, visit_date=now.date(), defaults={"user_agent": ua, "first_seen_at": now, "last_seen_at": now})
                try:
                    # Roll back only the failed insert so the enclosing transaction
                    # remains usable when a replay races with another request.
                    with transaction.atomic():
                        PublicVisitEvent.objects.create(event_id=event_id, visit=visit, path=path, ip_address=ip)
                except IntegrityError:
                    existing = PublicVisitEvent.objects.filter(event_id=event_id).first()
                    if not existing or existing.path != path or existing.ip_address != ip:
                        return Response(status=status.HTTP_400_BAD_REQUEST)
                    return Response(status=status.HTTP_204_NO_CONTENT)
                if not created:
                    PublicVisit.objects.filter(pk=visit.pk).update(last_seen_at=now, hit_count=F("hit_count") + 1, user_agent=ua)
        except IntegrityError:
            PublicVisit.objects.filter(path=path, ip_address=ip, visit_date=now.date()).update(last_seen_at=now, hit_count=F("hit_count") + 1, user_agent=ua)
        cleanup_expired_visits(settings.VISIT_CLEANUP_BATCH_SIZE)
        return Response(status=status.HTTP_204_NO_CONTENT)
from .permissions import IsOwnerOrReadOnly
from .serializers import (
    CompleteMediaSerializer,
    MediaLayoutSerializer,
    MediaMetadataSerializer,
    PostSerializer,
    PublicPostListSerializer,
    PublicPostSerializer,
    UploadIntentSerializer,
)
from .services import (
    PromotionFailed,
    promote_active_media,
    publish_post,
    validate_active_layout,
    withdraw_public_media,
    enqueue_storage_deletion,
    run_media_housekeeping,
)
from .storage import StorageError, StorageObjectNotFound, StorageRequestError, get_storage


class StorageAPIException(APIException):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    default_detail = "El servicio de almacenamiento no está disponible."


def queue_partial_public_cleanup(error):
    """Persist cleanup only after the failed DB transaction has rolled back."""
    for object_key in error.object_keys:
        enqueue_storage_deletion(error.bucket, object_key)


class HealthCheckView(APIView):
    permission_classes = [permissions.AllowAny]

    def get(self, request):
        return Response({"status": "ok"})


class PostViewSet(viewsets.ModelViewSet):
    serializer_class = PostSerializer
    permission_classes = [permissions.IsAuthenticated, IsOwnerOrReadOnly]

    def get_queryset(self):
        queryset = Post.objects.select_related("author").prefetch_related("media").filter(author=self.request.user)
        status_filter = self.request.query_params.get("status")
        if status_filter in Post.Status.values:
            queryset = queryset.filter(status=status_filter)
        return queryset

    def perform_create(self, serializer):
        serializer.save(author=self.request.user)

    def create(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        requested_status = serializer.validated_data.pop("status", Post.Status.DRAFT)
        try:
            with transaction.atomic():
                post = Post(author=request.user, status=Post.Status.DRAFT, **serializer.validated_data)
                post.save()
                if requested_status == Post.Status.PUBLISHED:
                    publish_post(post)
        except PromotionFailed as exc:
            queue_partial_public_cleanup(exc)
            raise StorageAPIException(str(exc)) from exc
        except StorageError as exc:
            raise StorageAPIException(str(exc)) from exc
        post.refresh_from_db()
        return Response(self.get_serializer(post).data, status=status.HTTP_201_CREATED)

    def update(self, request, *args, **kwargs):
        partial = kwargs.pop("partial", False)
        instance = self.get_object()
        serializer = self.get_serializer(instance, data=request.data, partial=partial)
        serializer.is_valid(raise_exception=True)
        requested_status = serializer.validated_data.pop("status", instance.status)
        try:
            with transaction.atomic():
                for attribute, value in serializer.validated_data.items():
                    setattr(instance, attribute, value)
                if requested_status == Post.Status.PUBLISHED and instance.status != Post.Status.PUBLISHED:
                    publish_post(instance)
                else:
                    if requested_status == Post.Status.DRAFT and instance.status == Post.Status.PUBLISHED:
                        withdraw_public_media(instance.media.select_for_update().all())
                    instance.status = requested_status
                    instance.save()
        except PromotionFailed as exc:
            queue_partial_public_cleanup(exc)
            raise StorageAPIException(str(exc)) from exc
        except StorageError as exc:
            raise StorageAPIException(str(exc)) from exc
        instance.refresh_from_db()
        return Response(self.get_serializer(instance).data)


class PublicPostViewSet(viewsets.ReadOnlyModelViewSet):
    serializer_class = PublicPostSerializer
    permission_classes = [permissions.AllowAny]
    lookup_field = "slug"

    def get_queryset(self):
        media = PostMedia.objects.filter(
            state=PostMedia.State.READY, position__isnull=False
        ).order_by("position")
        return Post.objects.filter(status=Post.Status.PUBLISHED).prefetch_related(
            Prefetch("media", queryset=media)
        ).order_by("-published_at", "-id")

    def get_serializer_context(self):
        return super().get_serializer_context()

    def get_serializer_class(self):
        return PublicPostListSerializer if self.action == "list" else PublicPostSerializer


class PostMediaBaseView(APIView):
    permission_classes = [permissions.IsAuthenticated]

    def get_post(self, post_id, lock=False):
        queryset = Post.objects.filter(pk=post_id, author=self.request.user)
        return (queryset.select_for_update() if lock else queryset).get()


class UploadIntentView(PostMediaBaseView):
    def post(self, request, post_id):
        serializer = UploadIntentSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        # Best-effort housekeeping must never turn a usable upload request into
        # an error; normal signed-URL creation below retains its own 503 policy.
        run_media_housekeeping()
        try:
            storage = get_storage()
        except StorageError as exc:
            raise StorageAPIException(str(exc)) from exc
        with transaction.atomic():
            try:
                post = self.get_post(post_id, lock=True)
            except Post.DoesNotExist:
                return Response(status=status.HTTP_404_NOT_FOUND)
            if post.media.count() >= settings.MEDIA_MAX_ITEMS_PER_POST:
                raise ValidationError({"media": ["Un post admite como máximo 10 elementos."]})
            if (
                serializer.validated_data["kind"] == PostMedia.Kind.VIDEO
                and post.media.filter(kind=PostMedia.Kind.VIDEO).count() >= settings.MEDIA_MAX_VIDEOS_PER_POST
            ):
                raise ValidationError({"media": ["Un post admite como máximo 2 vídeos."]})
            expires_at = timezone.now() + timedelta(seconds=settings.MEDIA_INTENT_TTL_SECONDS)
            media = PostMedia.objects.create(
                post=post,
                kind=serializer.validated_data["kind"],
                mime_type=serializer.validated_data["mime_type"],
                size_bytes=serializer.validated_data["size_bytes"],
                width=serializer.validated_data.get("width"),
                height=serializer.validated_data.get("height"),
                duration_seconds=serializer.validated_data.get("duration_seconds"),
                poster_mime_type=serializer.validated_data.get("poster_mime_type"),
                poster_size_bytes=serializer.validated_data.get("poster_size_bytes"),
                private_object_key="",  # assigned after UUID generation below
                upload_expires_at=expires_at,
            )
            media.private_object_key = f"posts/{post.pk}/{media.id}/asset"
            if media.kind == PostMedia.Kind.VIDEO:
                media.private_poster_key = f"posts/{post.pk}/{media.id}/poster"
            media.save(update_fields=["private_object_key", "private_poster_key", "updated_at"])
            try:
                upload_url = storage.create_upload_url(
                    media.private_object_key, settings.MEDIA_INTENT_TTL_SECONDS
                )
                poster_upload_url = (
                    storage.create_upload_url(media.private_poster_key, settings.MEDIA_INTENT_TTL_SECONDS)
                    if media.private_poster_key else None
                )
            except StorageError as exc:
                media.delete()
                raise StorageAPIException(str(exc)) from exc
        return Response(
            {
                "media_id": media.id,
                "upload_url": upload_url,
                "poster_upload_url": poster_upload_url,
                "expires_at": expires_at,
            },
            status=status.HTTP_201_CREATED,
        )


class CompleteMediaView(PostMediaBaseView):
    def post(self, request, post_id):
        serializer = CompleteMediaSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            storage = get_storage()
        except StorageError as exc:
            raise StorageAPIException(str(exc)) from exc
        failure = None
        with transaction.atomic():
            try:
                post = self.get_post(post_id, lock=True)
                media = post.media.select_for_update().get(pk=serializer.validated_data["media_id"])
            except (Post.DoesNotExist, PostMedia.DoesNotExist):
                return Response(status=status.HTTP_404_NOT_FOUND)
            if media.state != PostMedia.State.PENDING:
                raise ValidationError({"media_id": ["La carga ya fue completada o falló."]})
            if media.upload_expires_at and media.upload_expires_at <= timezone.now():
                media.state = PostMedia.State.FAILED
                media.save(update_fields=["state", "updated_at"])
                failure = {"media_id": ["La intención de carga ha vencido."]}
            else:
                try:
                    info = storage.get_object_info(storage.private_bucket, media.private_object_key)
                    poster_info = (
                        storage.get_object_info(storage.private_bucket, media.private_poster_key)
                        if media.private_poster_key else None
                    )
                except StorageObjectNotFound:
                    media.state = PostMedia.State.FAILED
                    media.save(update_fields=["state", "updated_at"])
                    failure = {"media": ["El objeto cargado no existe."]}
                except StorageRequestError as exc:
                    # The adapter's message deliberately includes only the
                    # upstream HTTP status, never Storage response content.
                    raise StorageAPIException(str(exc)) from exc
                except StorageError as exc:
                    raise StorageAPIException(str(exc)) from exc
                else:
                    valid = info.size_bytes == media.size_bytes and info.mime_type.lower() == media.mime_type.lower()
                    if poster_info:
                        valid = (
                            valid
                            and poster_info.mime_type.lower() == media.poster_mime_type.lower()
                            and poster_info.size_bytes == media.poster_size_bytes
                        )
                    if not valid:
                        media.state = PostMedia.State.FAILED
                        media.save(update_fields=["state", "updated_at"])
                        failure = {"media": ["El objeto cargado no coincide con el intent."]}
                    else:
                        media.state = PostMedia.State.READY
                        media.ready_at = timezone.now()
                        media.save(update_fields=["state", "ready_at", "updated_at"])
        if failure:
            raise ValidationError(failure)
        return Response({"media_id": media.id, "state": media.state, "ready_at": media.ready_at})


class MediaDetailView(PostMediaBaseView):
    def get_media(self, post_id, media_id):
        try:
            post = self.get_post(post_id)
            return post, post.media.get(pk=media_id)
        except (Post.DoesNotExist, PostMedia.DoesNotExist):
            return None, None

    def patch(self, request, post_id, media_id):
        post, media = self.get_media(post_id, media_id)
        if not media:
            return Response(status=status.HTTP_404_NOT_FOUND)
        serializer = MediaMetadataSerializer(media, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        was_public = post.status == Post.Status.PUBLISHED and media.position is not None and media.state == PostMedia.State.READY
        serializer.save()
        if was_public:
            post.save()  # existing signal invokes ISR after transaction commit
        return Response(serializer.data)

    def delete(self, request, post_id, media_id):
        with transaction.atomic():
            try:
                post = self.get_post(post_id, lock=True)
                media = post.media.select_for_update().get(pk=media_id)
            except (Post.DoesNotExist, PostMedia.DoesNotExist):
                return Response(status=status.HTTP_404_NOT_FOUND)
            was_public = post.status == Post.Status.PUBLISHED and media.position is not None and media.state == PostMedia.State.READY
            deleted_position = media.position
            media.delete()
            if deleted_position is not None:
                remaining = list(post.media.select_for_update().filter(position__isnull=False).order_by("position"))
                for position, item in enumerate(remaining):
                    if item.position != position:
                        item.position = position
                        item.save(update_fields=["position", "updated_at"])
                if remaining and not any(item.is_cover for item in remaining):
                    remaining[0].is_cover = True
                    remaining[0].save(update_fields=["is_cover", "updated_at"])
            if was_public:
                post.save()
        return Response(status=status.HTTP_204_NO_CONTENT)


class MediaLayoutView(PostMediaBaseView):
    def put(self, request, post_id):
        serializer = MediaLayoutSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            with transaction.atomic():
                try:
                    post = self.get_post(post_id, lock=True)
                except Post.DoesNotExist:
                    return Response(status=status.HTTP_404_NOT_FOUND)
                items = serializer.validated_data["items"]
                by_id = {item.id: item for item in post.media.select_for_update().filter(pk__in=[row["id"] for row in items])}
                if len(by_id) != len(items):
                    return Response(status=status.HTTP_404_NOT_FOUND)
                if any(item.state != PostMedia.State.READY for item in by_id.values()):
                    raise ValidationError({"items": ["Solo se puede incluir media lista."]})
                previous_active = list(
                    post.media.select_for_update().filter(
                        state=PostMedia.State.READY, position__isnull=False
                    )
                )
                # Remove conditional unique positions/cover before assigning the new layout.
                post.media.filter(position__isnull=False).update(position=None, is_cover=False)
                for row in items:
                    item = by_id[row["id"]]
                    item.position = row["position"]
                    item.is_cover = row["is_cover"]
                    item.save(update_fields=["position", "is_cover", "updated_at"])
                selected = [by_id[row["id"]] for row in sorted(items, key=lambda row: row["position"])]
                validate_active_layout(selected)
                if post.status == Post.Status.PUBLISHED:
                    promote_active_media(selected)
                    selected_ids = {item.id for item in selected}
                    withdraw_public_media(
                        [item for item in previous_active if item.id not in selected_ids]
                    )
                if "carousel_transition" in serializer.validated_data:
                    post.carousel_transition = serializer.validated_data["carousel_transition"]
                post.save()  # published posts invalidate ISR via the existing signal after commit
        except PromotionFailed as exc:
            queue_partial_public_cleanup(exc)
            raise StorageAPIException(str(exc)) from exc
        except StorageError as exc:
            raise StorageAPIException(str(exc)) from exc
        return Response(PostSerializer(post).data)


class MediaReadURLsView(PostMediaBaseView):
    def get(self, request, post_id):
        try:
            post = self.get_post(post_id)
        except Post.DoesNotExist:
            return Response(status=status.HTTP_404_NOT_FOUND)
        try:
            storage = get_storage()
            media = post.media.filter(state=PostMedia.State.READY, position__isnull=False).order_by("position")
            result = []
            for item in media:
                entry = {"id": item.id, "url": storage.create_signed_read_url(item.private_object_key, settings.MEDIA_READ_URL_TTL_SECONDS)}
                if item.kind == PostMedia.Kind.VIDEO and item.private_poster_key:
                    entry["poster_url"] = storage.create_signed_read_url(item.private_poster_key, settings.MEDIA_READ_URL_TTL_SECONDS)
                result.append(entry)
        except StorageError as exc:
            raise StorageAPIException() from exc
        expires_at = timezone.now() + timedelta(seconds=settings.MEDIA_READ_URL_TTL_SECONDS)
        return Response({"expires_at": expires_at.astimezone(dt_timezone.utc).isoformat().replace("+00:00", "Z"), "media": result})
