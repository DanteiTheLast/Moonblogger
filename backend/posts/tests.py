from datetime import timedelta
import hashlib
import io
import json
from unittest.mock import patch

from django.conf import settings
from django.contrib.auth.models import User
from django.core.management import call_command
from django.test import override_settings
from django.utils import timezone
from urllib.error import HTTPError, URLError
from rest_framework import status
from rest_framework.test import APITestCase

from .models import Post, PostMedia, StorageDeletionTask
from .storage import (
    ObjectInfo,
    StorageError,
    StorageObjectNotFound,
    StorageRequestError,
    SupabaseStorage,
)
from . import signals


def create_post(author, title, content="Contenido", **kwargs):
    return Post.objects.create(author=author, title=title, content=content, **kwargs)


class AuthAPITests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("moon", password="pass1234")

    def test_login_returns_tokens(self):
        resp = self.client.post(
            "/api/v1/auth/login/", {"username": "moon", "password": "pass1234"}
        )
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIn("access", resp.data)
        self.assertIn("refresh", resp.data)

    def test_login_invalid_credentials(self):
        resp = self.client.post(
            "/api/v1/auth/login/", {"username": "moon", "password": "incorrecta"}
        )
        self.assertEqual(resp.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_refresh(self):
        login = self.client.post(
            "/api/v1/auth/login/", {"username": "moon", "password": "pass1234"}
        )
        resp = self.client.post("/api/v1/auth/refresh/", {"refresh": login.data["refresh"]})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIn("access", resp.data)
        self.assertIn("refresh", resp.data)
        self.assertNotEqual(resp.data["refresh"], login.data["refresh"])

    def test_refresh_reuse_rotated_returns_401(self):
        login = self.client.post(
            "/api/v1/auth/login/", {"username": "moon", "password": "pass1234"}
        )
        old_refresh = login.data["refresh"]
        first = self.client.post("/api/v1/auth/refresh/", {"refresh": old_refresh})
        self.assertEqual(first.status_code, status.HTTP_200_OK)
        second = self.client.post("/api/v1/auth/refresh/", {"refresh": old_refresh})
        self.assertEqual(second.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertEqual(second.data["detail"], "Token is blacklisted")

    def test_refresh_chain_rotated_token_usable(self):
        login = self.client.post(
            "/api/v1/auth/login/", {"username": "moon", "password": "pass1234"}
        )
        first = self.client.post("/api/v1/auth/refresh/", {"refresh": login.data["refresh"]})
        self.assertEqual(first.status_code, status.HTTP_200_OK)
        second = self.client.post("/api/v1/auth/refresh/", {"refresh": first.data["refresh"]})
        self.assertEqual(second.status_code, status.HTTP_200_OK)
        self.assertIn("access", second.data)
        self.assertIn("refresh", second.data)

    def test_posts_requires_auth(self):
        resp = self.client.get("/api/v1/posts/")
        self.assertEqual(resp.status_code, status.HTTP_401_UNAUTHORIZED)


class PrivatePostAPITests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("moon", password="pass1234")
        self.client.force_authenticate(self.user)

    def test_create_post(self):
        resp = self.client.post(
            "/api/v1/posts/",
            {"title": "Mi primer post", "content": "Hola mundo", "status": "draft"},
        )
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["slug"], "mi-primer-post")
        self.assertEqual(resp.data["status"], "draft")
        self.assertIsNone(resp.data["published_at"])
        self.assertEqual(Post.objects.count(), 1)

    def test_create_published_sets_published_at(self):
        resp = self.client.post(
            "/api/v1/posts/", {"title": "Publicado", "content": "texto", "status": "published"}
        )
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertIsNotNone(resp.data["published_at"])

    def test_create_default_status_draft(self):
        resp = self.client.post("/api/v1/posts/", {"title": "Sin estado", "content": "texto"})
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["status"], "draft")

    def test_create_requires_title(self):
        resp = self.client.post("/api/v1/posts/", {"content": "texto"})
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)

    def test_create_blank_title_rejected(self):
        resp = self.client.post("/api/v1/posts/", {"title": "   ", "content": "texto"})
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)

    def test_create_blank_content_rejected(self):
        resp = self.client.post("/api/v1/posts/", {"title": "Título", "content": "   "})
        self.assertEqual(resp.status_code, status.HTTP_400_BAD_REQUEST)

    def test_slug_unique_with_suffix(self):
        create_post(self.user, title="Repetido")
        resp = self.client.post("/api/v1/posts/", {"title": "Repetido", "content": "texto"})
        self.assertEqual(resp.status_code, status.HTTP_201_CREATED)
        self.assertEqual(resp.data["slug"], "repetido-2")

    def test_list_includes_drafts(self):
        create_post(self.user, title="Borrador")
        create_post(self.user, title="Publicado", status="published")
        resp = self.client.get("/api/v1/posts/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        titles = [item["title"] for item in resp.data["results"]]
        self.assertEqual(set(titles), {"Borrador", "Publicado"})

    def test_list_filter_by_status(self):
        create_post(self.user, title="Borrador")
        create_post(self.user, title="Publicado", status="published")
        resp = self.client.get("/api/v1/posts/?status=published")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        titles = [item["title"] for item in resp.data["results"]]
        self.assertEqual(titles, ["Publicado"])

    def test_retrieve(self):
        post = create_post(self.user, title="Detalle")
        resp = self.client.get(f"/api/v1/posts/{post.id}/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["title"], "Detalle")

    def test_retrieve_other_user_404(self):
        other = User.objects.create_user("otra", password="x")
        post = create_post(other, title="De otra")
        resp = self.client.get(f"/api/v1/posts/{post.id}/")
        self.assertEqual(resp.status_code, status.HTTP_404_NOT_FOUND)

    def test_publish_sets_published_at(self):
        post = create_post(self.user, title="Borrador")
        resp = self.client.patch(f"/api/v1/posts/{post.id}/", {"status": "published"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIsNotNone(resp.data["published_at"])

    def test_unpublish_clears_published_at(self):
        post = create_post(self.user, title="Publicado", status="published")
        self.assertIsNotNone(post.published_at)
        resp = self.client.patch(f"/api/v1/posts/{post.id}/", {"status": "draft"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertIsNone(resp.data["published_at"])

    def test_update_title_keeps_slug(self):
        post = create_post(self.user, title="Titulo original")
        resp = self.client.patch(f"/api/v1/posts/{post.id}/", {"title": "Titulo nuevo"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["slug"], "titulo-original")

    def test_delete(self):
        post = create_post(self.user, title="A borrar")
        resp = self.client.delete(f"/api/v1/posts/{post.id}/")
        self.assertEqual(resp.status_code, status.HTTP_204_NO_CONTENT)
        self.assertEqual(Post.objects.count(), 0)


class PublicPostAPITests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("moon", password="pass1234")
        self.published = create_post(self.user, title="Publicada", status="published")
        self.draft = create_post(self.user, title="Borrador")

    def test_list_public_no_auth(self):
        resp = self.client.get("/api/v1/public/posts/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        titles = [item["title"] for item in resp.data["results"]]
        self.assertEqual(titles, ["Publicada"])

    def test_list_ordering(self):
        older = create_post(self.user, title="Vieja", status="published")
        older.published_at = self.published.published_at - timedelta(days=2)
        older.save()
        resp = self.client.get("/api/v1/public/posts/")
        titles = [item["title"] for item in resp.data["results"]]
        self.assertEqual(titles, ["Publicada", "Vieja"])

    def test_detail_public_by_slug(self):
        resp = self.client.get(f"/api/v1/public/posts/{self.published.slug}/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["title"], "Publicada")

    def test_detail_draft_returns_404(self):
        resp = self.client.get(f"/api/v1/public/posts/{self.draft.slug}/")
        self.assertEqual(resp.status_code, status.HTTP_404_NOT_FOUND)

    def test_health(self):
        resp = self.client.get("/api/v1/health/")
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["status"], "ok")


class WebhookSignalTests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("moon", password="pass1234")

    @patch("posts.signals._send_webhook")
    def test_publishing_post_invalidates_public_pages(self, send_webhook):
        with self.captureOnCommitCallbacks(execute=True):
            create_post(self.user, title="Publicado", status="published")

        send_webhook.assert_called_once_with(Post.Status.DRAFT, Post.Status.PUBLISHED)

    @patch("posts.signals._send_webhook")
    def test_unpublishing_post_invalidates_public_pages(self, send_webhook):
        post = create_post(self.user, title="Publicado", status="published")

        with self.captureOnCommitCallbacks(execute=True):
            post.status = Post.Status.DRAFT
            post.save()

        send_webhook.assert_called_once_with(Post.Status.PUBLISHED, Post.Status.DRAFT)

    @patch("posts.signals._send_webhook")
    def test_editing_published_post_invalidates_public_pages(self, send_webhook):
        post = create_post(self.user, title="Publicado", status="published")

        with self.captureOnCommitCallbacks(execute=True):
            post.content = "Contenido actualizado"
            post.save()

        send_webhook.assert_called_once_with(Post.Status.PUBLISHED, Post.Status.PUBLISHED)

    @patch("posts.signals._send_webhook")
    def test_deleting_published_post_invalidates_public_pages(self, send_webhook):
        post = create_post(self.user, title="Publicado", status="published")

        with self.captureOnCommitCallbacks(execute=True):
            post.delete()

        send_webhook.assert_called_once_with(Post.Status.PUBLISHED, Post.Status.DRAFT)

    @patch("posts.signals.urllib.request.Request")
    def test_draft_only_changes_do_not_send_webhook(self, request):
        with patch.dict(
            "os.environ",
            {
                "WEB_REVALIDATE_URL": "https://web.test/api/revalidate",
                "WEB_REVALIDATE_SECRET": "test-secret",
            },
            clear=False,
        ):
            signals._send_webhook(Post.Status.DRAFT, Post.Status.DRAFT)

        request.assert_not_called()

    @patch("posts.signals.urllib.request.Request")
    def test_deleting_draft_does_not_send_webhook(self, request):
        post = create_post(self.user, title="Borrador")
        with patch.dict(
            "os.environ",
            {
                "WEB_REVALIDATE_URL": "https://web.test/api/revalidate",
                "WEB_REVALIDATE_SECRET": "test-secret",
            },
            clear=False,
        ):
            with self.captureOnCommitCallbacks(execute=True):
                post.delete()

        request.assert_not_called()

    @patch("posts.signals.threading.Thread")
    @patch("posts.signals.urllib.request.Request")
    def test_webhook_sends_sha256_secret(self, request, thread):
        secret = "test-secret"
        with patch.dict(
            "os.environ",
            {
                "WEB_REVALIDATE_URL": "https://web.test/api/revalidate",
                "WEB_REVALIDATE_SECRET": secret,
            },
            clear=False,
        ):
            signals._send_webhook(Post.Status.DRAFT, Post.Status.PUBLISHED)

        request.assert_called_once()
        headers = request.call_args.kwargs["headers"]
        self.assertEqual(
            headers["X-Revalidate-Secret"],
            hashlib.sha256(secret.encode("utf-8")).hexdigest(),
        )
        thread.return_value.start.assert_called_once()


class FakeStorage:
    private_bucket = "private"
    public_bucket = "public"

    def __init__(self):
        self.info = {}
        self.promoted = []
        self.deleted = []
        self.intents = []

    def create_upload_url(self, key, ttl):
        self.intents.append((key, ttl))
        return f"https://storage.test/upload/{key}"

    def get_object_info(self, bucket, key):
        return self.info[(bucket, key)]

    def promote(self, key):
        self.promoted.append(key)

    def delete(self, bucket, key):
        self.deleted.append((bucket, key))


class MediaHousekeepingServiceTests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("housekeeping", password="pass1234")
        self.post = create_post(self.user, "Housekeeping")
        self.storage = FakeStorage()

    def media(self, **kwargs):
        defaults = {
            "post": self.post,
            "kind": PostMedia.Kind.IMAGE,
            "mime_type": "image/jpeg",
            "size_bytes": 1,
            "private_object_key": "asset",
        }
        defaults.update(kwargs)
        return PostMedia.objects.create(**defaults)

    def test_service_removes_five_expired_intents_and_processes_their_tasks(self):
        expired = [
            self.media(
                state=PostMedia.State.PENDING,
                private_object_key=f"expired/{number}",
                upload_expires_at=timezone.now() - timedelta(seconds=1),
            )
            for number in range(5)
        ]

        from .services import run_media_housekeeping

        with patch("posts.services.get_storage", return_value=self.storage):
            result = run_media_housekeeping()

        self.assertEqual(result.expired_intents, 5)
        self.assertEqual(result.deleted_objects, 5)
        self.assertFalse(PostMedia.objects.filter(pk__in=[item.pk for item in expired]).exists())
        self.assertEqual(StorageDeletionTask.objects.filter(completed_at__isnull=False).count(), 5)
        self.assertEqual(len(self.storage.deleted), 5)

    def test_service_never_removes_nonexpired_or_ready_media(self):
        pending = self.media(
            state=PostMedia.State.PENDING,
            private_object_key="pending-future",
            upload_expires_at=timezone.now() + timedelta(minutes=1),
        )
        failed = self.media(
            state=PostMedia.State.FAILED,
            private_object_key="failed-future",
            upload_expires_at=timezone.now() + timedelta(minutes=1),
        )
        ready = self.media(
            state=PostMedia.State.READY,
            private_object_key="ready-expired",
            public_object_key="ready-public",
            ready_at=timezone.now(),
            upload_expires_at=timezone.now() - timedelta(seconds=1),
        )

        from .services import run_media_housekeeping

        with patch("posts.services.get_storage", return_value=self.storage):
            result = run_media_housekeeping()

        self.assertEqual(result.expired_intents, 0)
        self.assertEqual(PostMedia.objects.filter(pk__in=[pending.pk, failed.pk, ready.pk]).count(), 3)
        self.assertFalse(StorageDeletionTask.objects.exists())


class MediaCleanupCommandTests(APITestCase):
    @patch("posts.management.commands.cleanup_media_storage.run_media_housekeeping")
    def test_command_delegates_to_housekeeping_service_with_requested_limit(self, housekeeping):
        from .services import MediaHousekeepingResult

        housekeeping.return_value = MediaHousekeepingResult(
            expired_intents=1,
            deleted_objects=1,
        )
        output = io.StringIO()

        call_command("cleanup_media_storage", "--limit", "5", stdout=output)

        housekeeping.assert_called_once_with(limit=5)
        self.assertIn("Intents vencidos: 1; objetos eliminados: 1", output.getvalue())


class FakeHTTPResponse:
    def __init__(self, body=b"", headers=None):
        self.body = body
        self.headers = headers or {}
        self.read_calls = 0

    def read(self):
        self.read_calls += 1
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class SupabaseStorageAdapterTests(APITestCase):
    def setUp(self):
        self.storage = SupabaseStorage("https://project.test", "not-a-real-secret", "private", "public")

    @patch("posts.storage.urlopen")
    def test_signed_upload_accepts_current_url_response(self, urlopen_mock):
        urlopen_mock.return_value = FakeHTTPResponse(json.dumps({"url": "https://upload.test/signed"}).encode())
        self.assertEqual(self.storage.create_upload_url("posts/a/asset", 60), "https://upload.test/signed")
        request = urlopen_mock.call_args.args[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertIn("/object/upload/sign/private/posts/a/asset", request.full_url)
        self.assertNotIn("not-a-real-secret", request.full_url)
        self.assertIsNone(request.data)

    @patch("posts.storage.urlopen")
    def test_object_info_uses_head_exact_object_path_and_asset_headers(self, urlopen_mock):
        response = FakeHTTPResponse(
            headers={"Content-Length": "123", "Content-Type": "image/jpeg"}
        )
        urlopen_mock.return_value = response
        self.assertEqual(self.storage.get_object_info("private", "posts/a/asset"), ObjectInfo(123, "image/jpeg"))
        request = urlopen_mock.call_args.args[0]
        self.assertEqual(request.get_method(), "HEAD")
        self.assertEqual(
            request.full_url,
            "https://project.test/storage/v1/object/private/posts/a/asset",
        )
        self.assertNotIn("/object/info/", request.full_url)
        self.assertEqual(response.read_calls, 0)

    @patch("posts.storage.urlopen")
    def test_object_info_rejects_missing_or_invalid_headers(self, urlopen_mock):
        invalid_headers = (
            {},
            {"Content-Type": "image/jpeg"},
            {"Content-Length": "not-an-integer", "Content-Type": "image/jpeg"},
            {"Content-Length": "-1", "Content-Type": "image/jpeg"},
            {"Content-Length": "123"},
            {"Content-Length": "123", "Content-Type": "   "},
            {"Content-Length": "123", "Content-Type": 42},
        )
        for headers in invalid_headers:
            with self.subTest(headers=headers):
                urlopen_mock.return_value = FakeHTTPResponse(headers=headers)
                with self.assertRaisesRegex(
                    StorageError, "Supabase Storage devolvió encabezados de objeto inválidos."
                ):
                    self.storage.get_object_info("private", "posts/a/asset")

    @patch("posts.storage.urlopen")
    def test_object_info_classifies_supabase_not_found_errors(self, urlopen_mock):
        cases = (
            (400, {"code": "NoSuchKey"}),
            (400, {"statusCode": "404"}),
            (404, None),
        )
        for upstream_status, data in cases:
            with self.subTest(upstream_status=upstream_status, data=data):
                urlopen_mock.side_effect = HTTPError(
                    "https://project.test",
                    upstream_status,
                    "missing",
                    {},
                    io.BytesIO(json.dumps(data).encode()) if data else None,
                )
                with self.assertRaises(StorageObjectNotFound):
                    self.storage.get_object_info("private", "posts/a/asset")

    @patch("posts.storage.urlopen")
    def test_object_info_request_error_is_safe_and_logs_limited_diagnostics(self, urlopen_mock):
        signed_url = "https://project.test/object/private/posts/secret/asset?token=secret-token"
        raw_body = "provider response containing secret-token and posts/secret/asset"
        urlopen_mock.side_effect = HTTPError(
            signed_url,
            403,
            "forbidden",
            {"X-Request-ID": "request-123", "X-Unrelated": "ignore-me"},
            io.BytesIO(json.dumps({"code": "AccessDenied", "message": raw_body}).encode()),
        )

        with self.assertLogs("posts.storage", level="WARNING") as logs, self.assertRaises(
            StorageRequestError
        ) as raised:
            self.storage.get_object_info("private", "posts/secret/asset")

        error = raised.exception
        self.assertEqual(error.http_status, 403)
        self.assertEqual(error.status_code, 403)
        self.assertEqual(error.provider_code, "AccessDenied")
        self.assertEqual(error.request_id, "request-123")
        self.assertEqual(
            str(error), "Supabase Storage rechazó HEAD del objeto (HTTP 403)."
        )
        log_output = "\n".join(logs.output)
        self.assertIn("operation=object_info status=403 provider_code=AccessDenied request_id=request-123", log_output)
        for forbidden in (signed_url, "secret-token", "posts/secret/asset", raw_body):
            self.assertNotIn(forbidden, str(error))
            self.assertNotIn(forbidden, log_output)

    @patch("posts.storage.urlopen")
    def test_object_info_uses_distinct_safe_errors_for_network_and_header_failures(self, urlopen_mock):
        urlopen_mock.side_effect = URLError("https://project.test/posts/secret/asset?token=secret-token")
        with self.assertRaisesRegex(
            StorageError, "Supabase Storage no está disponible para verificar el objeto."
        ) as network_error:
            self.storage.get_object_info("private", "posts/secret/asset")

        urlopen_mock.side_effect = None
        urlopen_mock.return_value = FakeHTTPResponse(headers=object())
        with self.assertRaisesRegex(
            StorageError, "Supabase Storage devolvió encabezados de objeto inválidos."
        ) as header_error:
            self.storage.get_object_info("private", "posts/secret/asset")

        self.assertNotEqual(str(network_error.exception), str(header_error.exception))
        for forbidden in ("secret-token", "posts/secret/asset", "project.test"):
            self.assertNotIn(forbidden, str(network_error.exception))
            self.assertNotIn(forbidden, str(header_error.exception))

    @patch("posts.storage.urlopen")
    def test_delete_uses_documented_endpoint_and_404_is_idempotent(self, urlopen_mock):
        urlopen_mock.return_value = FakeHTTPResponse()
        self.storage.delete("public", "posts/a/asset")
        request = urlopen_mock.call_args.args[0]
        self.assertEqual(request.get_method(), "DELETE")
        self.assertIn("/object/public/posts/a/asset", request.full_url)
        urlopen_mock.side_effect = HTTPError("https://project.test", 404, "missing", {}, io.BytesIO())
        self.storage.delete("public", "posts/a/asset")


@override_settings(
    MEDIA_STORAGE_URL="https://storage.test",
    MEDIA_STORAGE_SERVICE_ROLE_KEY="not-a-real-secret",
    MEDIA_STORAGE_PRIVATE_BUCKET="private",
    MEDIA_STORAGE_PUBLIC_BUCKET="public",
)
class MediaAPITests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("moon-media", password="pass1234")
        self.other = User.objects.create_user("other-media", password="pass1234")
        self.client.force_authenticate(self.user)
        self.post = create_post(self.user, "Con media")
        self.storage = FakeStorage()

    def storage_patches(self):
        return patch("posts.views.get_storage", return_value=self.storage), patch(
            "posts.services.get_storage", return_value=self.storage
        )

    def ready_media(self, **kwargs):
        defaults = {
            "post": self.post,
            "kind": PostMedia.Kind.IMAGE,
            "state": PostMedia.State.READY,
            "mime_type": "image/jpeg",
            "size_bytes": 100,
            "private_object_key": "posts/1/example/asset",
            "ready_at": timezone.now(),
        }
        defaults.update(kwargs)
        return PostMedia.objects.create(**defaults)

    def test_previous_post_payload_remains_accepted(self):
        response = self.client.post("/api/v1/posts/", {"title": "Compat", "content": "texto"})
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.data["carousel_transition"], "slide")

    def test_media_routes_hide_other_owner(self):
        other_post = create_post(self.other, "Ajeno")
        response = self.client.post(
            f"/api/v1/posts/{other_post.id}/media/upload-intents/",
            {"kind": "image", "mime_type": "image/jpeg", "size_bytes": 20},
        )
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)

    def test_upload_intent_validates_limits_and_creates_uuid_key(self):
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/upload-intents/",
                {"kind": "image", "mime_type": "image/png", "size_bytes": 123, "width": 10, "height": 20},
            )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        media = PostMedia.objects.get(pk=response.data["media_id"])
        self.assertEqual(media.state, PostMedia.State.PENDING)
        self.assertIn(str(media.id), media.private_object_key)
        self.assertNotIn("service", response.data["upload_url"])
        too_big = self.client.post(
            f"/api/v1/posts/{self.post.id}/media/upload-intents/",
            {"kind": "image", "mime_type": "image/jpeg", "size_bytes": settings.MEDIA_MAX_IMAGE_BYTES + 1},
        )
        self.assertEqual(too_big.status_code, status.HTTP_400_BAD_REQUEST)

    def test_upload_intent_continues_when_housekeeping_storage_delete_fails(self):
        task = StorageDeletionTask.objects.create(bucket="private", object_key="retry-me")
        with patch("posts.views.get_storage", return_value=self.storage), patch(
            "posts.services.get_storage", return_value=self.storage
        ), patch.object(self.storage, "delete", side_effect=StorageError("temporary failure")):
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/upload-intents/",
                {"kind": "image", "mime_type": "image/jpeg", "size_bytes": 1},
            )

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        task.refresh_from_db()
        self.assertIsNone(task.completed_at)
        self.assertEqual(task.attempts, 1)

    def test_upload_intent_enforces_ten_elements_and_reports_unconfigured_storage(self):
        for number in range(10):
            self.ready_media(private_object_key=f"asset-{number}")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            limited = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/upload-intents/",
                {"kind": "image", "mime_type": "image/jpeg", "size_bytes": 1},
            )
        self.assertEqual(limited.status_code, status.HTTP_400_BAD_REQUEST)
        with patch("posts.views.get_storage", side_effect=StorageError("sin configurar")):
            unavailable = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/upload-intents/",
                {"kind": "image", "mime_type": "image/jpeg", "size_bytes": 1},
            )
        self.assertEqual(unavailable.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)

    def test_video_requires_poster_and_respects_video_limit(self):
        invalid = self.client.post(
            f"/api/v1/posts/{self.post.id}/media/upload-intents/",
            {"kind": "video", "mime_type": "video/mp4", "size_bytes": 10, "duration_seconds": 10},
        )
        self.assertEqual(invalid.status_code, status.HTTP_400_BAD_REQUEST)
        self.ready_media(kind="video", private_poster_key="poster-one")
        self.ready_media(kind="video", private_object_key="two", private_poster_key="poster-two")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            limited = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/upload-intents/",
                {
                    "kind": "video", "mime_type": "video/mp4", "size_bytes": 10,
                    "duration_seconds": 10, "poster_mime_type": "image/jpeg", "poster_size_bytes": 10,
                },
            )
        self.assertEqual(limited.status_code, status.HTTP_400_BAD_REQUEST)

    def test_complete_checks_remote_metadata(self):
        media = self.ready_media(state=PostMedia.State.PENDING, ready_at=None, upload_expires_at=timezone.now() + timedelta(minutes=2))
        self.storage.info[("private", media.private_object_key)] = ObjectInfo(100, "image/jpeg")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(media.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        media.refresh_from_db()
        self.assertEqual(media.state, PostMedia.State.READY)

    @patch("posts.storage.urlopen")
    def test_complete_marks_ready_with_supabase_object_info(self, urlopen_mock):
        media = self.ready_media(
            state=PostMedia.State.PENDING,
            ready_at=None,
            upload_expires_at=timezone.now() + timedelta(minutes=2),
        )
        urlopen_mock.return_value = FakeHTTPResponse(
            headers={
                "Content-Length": str(media.size_bytes),
                "Content-Type": media.mime_type,
            }
        )
        storage = SupabaseStorage("https://storage.test", "not-a-real-secret", "private", "public")
        with patch("posts.views.get_storage", return_value=storage):
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(media.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        media.refresh_from_db()
        self.assertEqual(media.state, PostMedia.State.READY)
        request = urlopen_mock.call_args.args[0]
        self.assertEqual(request.get_method(), "HEAD")
        self.assertEqual(
            request.full_url,
            f"https://storage.test/storage/v1/object/private/{media.private_object_key}",
        )

    @patch("posts.storage.urlopen")
    def test_complete_returns_safe_metadata_storage_diagnostics(self, urlopen_mock):
        signed_url = "https://storage.test/object/private/posts/secret/asset?token=secret-token"
        raw_body = "raw storage body with secret-token and posts/secret/asset"
        media = self.ready_media(
            state=PostMedia.State.PENDING,
            ready_at=None,
            upload_expires_at=timezone.now() + timedelta(minutes=2),
            private_object_key="posts/secret/asset",
        )
        storage = SupabaseStorage("https://storage.test", "not-a-real-secret", "private", "public")

        for upstream_status in (400, 403, 500):
            with self.subTest(upstream_status=upstream_status):
                urlopen_mock.side_effect = HTTPError(
                    signed_url,
                    upstream_status,
                    "upstream failure",
                    {"X-Request-ID": "request-123"},
                    io.BytesIO(json.dumps({"code": "AccessDenied", "message": raw_body}).encode()),
                )
                with patch("posts.views.get_storage", return_value=storage):
                    response = self.client.post(
                        f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(media.id)}
                    )

                self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
                self.assertEqual(
                    response.data["detail"],
                    f"Supabase Storage rechazó HEAD del objeto (HTTP {upstream_status}).",
                )
                response_text = str(response.data)
                for forbidden in (signed_url, "secret-token", "posts/secret/asset", raw_body, "AccessDenied"):
                    self.assertNotIn(forbidden, response_text)

    def test_complete_failure_states_are_persisted(self):
        expired = self.ready_media(
            state=PostMedia.State.PENDING, ready_at=None,
            upload_expires_at=timezone.now() - timedelta(seconds=1), private_object_key="expired",
        )
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(expired.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        expired.refresh_from_db()
        self.assertEqual(expired.state, PostMedia.State.FAILED)

        absent = self.ready_media(
            state=PostMedia.State.PENDING, ready_at=None,
            upload_expires_at=timezone.now() + timedelta(minutes=1), private_object_key="absent",
        )
        with patch("posts.views.get_storage", return_value=self.storage), patch.object(
            self.storage, "get_object_info", side_effect=StorageObjectNotFound("missing")
        ):
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(absent.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        absent.refresh_from_db()
        self.assertEqual(absent.state, PostMedia.State.FAILED)

        invalid = self.ready_media(
            state=PostMedia.State.PENDING, ready_at=None,
            upload_expires_at=timezone.now() + timedelta(minutes=1), private_object_key="invalid",
        )
        self.storage.info[("private", "invalid")] = ObjectInfo(99, "image/jpeg")
        with patch("posts.views.get_storage", return_value=self.storage):
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(invalid.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        invalid.refresh_from_db()
        self.assertEqual(invalid.state, PostMedia.State.FAILED)

    def test_complete_validates_poster_exactly(self):
        media = self.ready_media(
            kind=PostMedia.Kind.VIDEO, state=PostMedia.State.PENDING, ready_at=None,
            private_object_key="video", private_poster_key="poster", poster_mime_type="image/jpeg",
            poster_size_bytes=30, upload_expires_at=timezone.now() + timedelta(minutes=1),
        )
        self.storage.info[("private", "video")] = ObjectInfo(100, "image/jpeg")
        # Wrong media MIME for the asset makes the completion invalid too; use a valid MP4 asset.
        media.mime_type = "video/mp4"
        media.save(update_fields=["mime_type"])
        self.storage.info[("private", "video")] = ObjectInfo(100, "video/mp4")
        self.storage.info[("private", "poster")] = ObjectInfo(31, "image/jpeg")
        with patch("posts.views.get_storage", return_value=self.storage):
            response = self.client.post(
                f"/api/v1/posts/{self.post.id}/media/complete/", {"media_id": str(media.id)}
            )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        media.refresh_from_db()
        self.assertEqual(media.state, PostMedia.State.FAILED)

    def test_layout_is_atomic_and_requires_ready_cover_transition(self):
        first = self.ready_media()
        second = self.ready_media(private_object_key="second")
        response = self.client.put(
            f"/api/v1/posts/{self.post.id}/media/layout/",
            {"items": [{"id": str(first.id), "position": 0, "is_cover": True}, {"id": str(second.id), "position": 1, "is_cover": False}], "carousel_transition": "fade"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.post.refresh_from_db()
        self.assertEqual(self.post.carousel_transition, "fade")
        first.refresh_from_db()
        self.assertTrue(first.is_cover)
        pending = self.ready_media(state=PostMedia.State.PENDING, private_object_key="pending")
        invalid = self.client.put(
            f"/api/v1/posts/{self.post.id}/media/layout/",
            {"items": [{"id": str(pending.id), "position": 0, "is_cover": True}]}, format="json",
        )
        self.assertEqual(invalid.status_code, status.HTTP_400_BAD_REQUEST)
        first.refresh_from_db()
        self.assertEqual(first.position, 0)  # failed layout did not replace it

    def test_publish_promotes_active_assets_and_failure_keeps_draft(self):
        media = self.ready_media(position=0, is_cover=True)
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"})
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(self.storage.promoted, [media.private_object_key])
        media.refresh_from_db()
        self.assertEqual(media.public_object_key, media.private_object_key)
        failed_post = create_post(self.user, "Falla")
        self.ready_media(post=failed_post, position=0, is_cover=True, private_object_key="will-fail")
        with patch("posts.services.get_storage", side_effect=StorageError("down")):
            response = self.client.patch(f"/api/v1/posts/{failed_post.id}/", {"status": "published"})
        self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
        failed_post.refresh_from_db()
        self.assertEqual(failed_post.status, Post.Status.DRAFT)

    def test_public_visibility_and_isr_for_visible_metadata_change(self):
        media = self.ready_media(position=0, is_cover=True, public_object_key="public/asset")
        self.post.status = Post.Status.PUBLISHED
        self.post.save()
        with patch("posts.signals._send_webhook") as webhook:
            with self.captureOnCommitCallbacks(execute=True):
                response = self.client.patch(
                    f"/api/v1/posts/{self.post.id}/media/{media.id}/", {"alt_text": "luna"}
                )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        webhook.assert_called_once_with(Post.Status.PUBLISHED, Post.Status.PUBLISHED)
        public = self.client.get(f"/api/v1/public/posts/{self.post.slug}/")
        self.assertEqual(public.status_code, status.HTTP_200_OK)
        self.assertEqual(public.data["media_count"], 1)
        self.assertEqual(public.data["media"][0]["url"], "https://storage.test/storage/v1/object/public/public/public/asset")
        self.assertNotIn("private_object_key", public.data["media"][0])
        private = self.client.get(f"/api/v1/posts/{self.post.id}/")
        self.assertNotIn("private_object_key", private.data["media"][0])
        self.assertNotIn("private_poster_key", private.data["media"][0])
        public_list = self.client.get("/api/v1/public/posts/")
        self.assertNotIn("media", public_list.data["results"][0])

    def test_cleanup_removes_expired_pending_intent(self):
        expired = self.ready_media(
            state=PostMedia.State.PENDING,
            ready_at=None,
            private_object_key="expired/asset",
            upload_expires_at=timezone.now() - timedelta(seconds=1),
        )
        with patch("posts.services.get_storage", return_value=self.storage):
            call_command("cleanup_media_storage")
        self.assertFalse(PostMedia.objects.filter(pk=expired.pk).exists())
        self.assertIn(("private", "expired/asset"), self.storage.deleted)

    def test_delete_enqueues_outbox_and_cleanup_is_idempotent(self):
        media = self.ready_media(private_object_key="private/asset", public_object_key="public/asset")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.delete(f"/api/v1/posts/{self.post.id}/media/{media.id}/")
            self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
            self.assertEqual(StorageDeletionTask.objects.count(), 2)
            call_command("cleanup_media_storage")
            call_command("cleanup_media_storage")
        self.assertEqual(StorageDeletionTask.objects.filter(completed_at__isnull=False).count(), 2)
        self.assertEqual(len(self.storage.deleted), 2)

    def test_cleanup_treats_missing_object_as_success_and_outbox_is_unique(self):
        task = StorageDeletionTask.objects.create(bucket="public", object_key="gone")
        self.assertEqual(
            StorageDeletionTask.objects.get_or_create(bucket="public", object_key="gone")[1], False
        )
        with patch("posts.services.get_storage", return_value=self.storage), patch.object(
            self.storage, "delete", side_effect=StorageObjectNotFound("gone")
        ):
            call_command("cleanup_media_storage")
        task.refresh_from_db()
        self.assertIsNotNone(task.completed_at)
        from .services import enqueue_storage_deletion

        enqueue_storage_deletion("public", "gone")
        task.refresh_from_db()
        self.assertIsNone(task.completed_at)

    def test_unpublish_and_layout_removal_withdraw_public_objects(self):
        current = self.ready_media(
            position=0, is_cover=True, private_object_key="old-private", public_object_key="old-public",
            private_poster_key="old-poster-private", public_poster_key="old-poster-public",
        )
        self.post.status = Post.Status.PUBLISHED
        self.post.save()
        response = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "draft"})
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        current.refresh_from_db()
        self.assertIsNone(current.public_object_key)
        self.assertEqual(StorageDeletionTask.objects.filter(bucket="public").count(), 2)

        self.post.status = Post.Status.PUBLISHED
        self.post.save()
        current.public_object_key = "old-public-again"
        current.public_poster_key = "old-poster-public-again"
        current.save()
        replacement = self.ready_media(private_object_key="new-private")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            response = self.client.put(
                f"/api/v1/posts/{self.post.id}/media/layout/",
                {"items": [{"id": str(replacement.id), "position": 0, "is_cover": True}]}, format="json",
            )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        current.refresh_from_db()
        replacement.refresh_from_db()
        self.assertIsNone(current.public_object_key)
        self.assertEqual(replacement.public_object_key, "new-private")
