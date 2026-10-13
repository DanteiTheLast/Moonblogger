from datetime import timedelta
import hashlib
import hmac
import io
import json
import time
import uuid
from unittest.mock import patch

from django.conf import settings
from django.contrib.auth.models import User
from django.core.management import call_command
from django.core.cache import cache
from django.test import override_settings
from django.utils import timezone
from urllib.error import HTTPError, URLError
from rest_framework import status
from rest_framework.test import APITestCase

from .models import Post, PostMedia, PublicVisit, PublicVisitEvent, StorageDeletionTask
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
        cache.clear()
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

    def test_unpublish_preserves_published_at(self):
        post = create_post(self.user, title="Publicado", status="published")
        self.assertIsNotNone(post.published_at)
        resp = self.client.patch(f"/api/v1/posts/{post.id}/", {"status": "draft"})
        self.assertEqual(resp.status_code, status.HTTP_200_OK)
        self.assertEqual(resp.data["published_at"], post.published_at.isoformat().replace("+00:00", "Z"))

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


@override_settings(VISIT_FORWARDING_SECRET="visit-test-secret")
class PublicVisitAPITests(APITestCase):
    def setUp(self):
        cache.clear()
        self.user = User.objects.create_user("visitor-tests", password="pass1234")
        self.published = create_post(self.user, "Visitor post", status="published")

    def signed_headers(self, *, path="/", ip="203.0.113.10", user_agent="Web\n  Browser", event_id=None):
        timestamp = str(int(time.time()))
        event_id = event_id or str(uuid.uuid4())
        sanitized_user_agent = " ".join(user_agent.split())[:512]
        from .analytics import canonical_ip
        payload = f"{timestamp}\n{event_id}\n{canonical_ip(ip)}\n{path}\n{sanitized_user_agent}".encode()
        signature = hmac.new(
            settings.VISIT_FORWARDING_SECRET.encode(), payload, hashlib.sha256
        ).hexdigest()
        return {
            "HTTP_X_VISITOR_TIMESTAMP": timestamp,
            "HTTP_X_VISITOR_IP": ip,
            "HTTP_X_VISITOR_USER_AGENT": user_agent,
            "HTTP_X_VISITOR_SIGNATURE": signature,
            "event_id": event_id,
        }

    def post_signed(self, headers, **data):
        event_id = headers.pop("event_id")
        return self.client.post(
            "/api/v1/internal/public-visits/", {"path": data.pop("path", "/"), "event_id": event_id},
            format="json", **headers, **data
        )

    def test_signed_visit_uses_exact_forwarded_headers_and_persists_sanitized_ua(self):
        headers = self.signed_headers()
        response = self.client.post(
            "/api/v1/internal/public-visits/", {"path": "/", "event_id": headers.pop("event_id")},
            format="json", HTTP_USER_AGENT="connection-agent", **headers
        )

        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        visit = PublicVisit.objects.get()
        self.assertEqual(visit.ip_address, "203.0.113.10")
        self.assertEqual(visit.user_agent, "Web Browser")
        self.assertNotEqual(visit.user_agent, "connection-agent")
        self.assertEqual(int(headers["HTTP_X_VISITOR_TIMESTAMP"]), int(time.time()))

    def test_invalid_signature_does_not_persist_or_leak_details(self):
        headers = self.signed_headers()
        headers["HTTP_X_VISITOR_SIGNATURE"] = "invalid-signature"
        response = self.client.post(
            "/api/v1/internal/public-visits/", {"path": "/", "event_id": headers.pop("event_id")},
            format="json", HTTP_USER_AGENT="secret-connection-agent", **headers
        )

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(PublicVisit.objects.count(), 0)
        body = response.content.decode()
        self.assertNotIn("invalid-signature", body)
        self.assertNotIn("secret-connection-agent", body)

    def test_ip_is_not_read_from_request_body(self):
        headers = self.signed_headers(ip="203.0.113.10")
        response = self.client.post(
            "/api/v1/internal/public-visits/",
            {"path": "/", "ip": "198.51.100.99", "event_id": headers.pop("event_id")},
            format="json", **headers
        )

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(PublicVisit.objects.count(), 0)

    def test_replay_same_event_id_is_idempotent(self):
        headers = self.signed_headers()
        first = self.post_signed(headers.copy())
        replay = self.post_signed(headers.copy())
        self.assertEqual(first.status_code, status.HTTP_204_NO_CONTENT)
        self.assertEqual(replay.status_code, status.HTTP_204_NO_CONTENT)
        self.assertEqual(PublicVisit.objects.get().hit_count, 1)

    def test_same_event_id_with_different_path_or_ip_is_rejected(self):
        headers = self.signed_headers()
        self.assertEqual(self.post_signed(headers.copy()).status_code, 204)
        changed_path = self.signed_headers(path=f"/posts/{self.published.slug}", event_id=headers["event_id"])
        self.assertEqual(self.post_signed(changed_path, path=f"/posts/{self.published.slug}").status_code, 400)
        changed_ip = self.signed_headers(ip="198.51.100.7", event_id=headers["event_id"])
        self.assertEqual(self.post_signed(changed_ip).status_code, 400)

    def test_distinct_event_ids_increment_hit_count(self):
        self.assertEqual(self.post_signed(self.signed_headers()).status_code, 204)
        self.assertEqual(self.post_signed(self.signed_headers()).status_code, 204)
        self.assertEqual(PublicVisit.objects.get().hit_count, 2)

    def test_timestamp_must_be_integer_and_current(self):
        for timestamp in ("not-an-integer", str(int(time.time()) - 1000), str(int(time.time()) + 1000)):
            with self.subTest(timestamp=timestamp):
                headers = self.signed_headers()
                headers["HTTP_X_VISITOR_TIMESTAMP"] = timestamp
                self.assertEqual(self.post_signed(headers).status_code, 400)

    def test_ipv6_is_canonicalized(self):
        headers = self.signed_headers(ip="2001:0db8:0000:0000:0000:0000:0000:0001")
        self.assertEqual(self.post_signed(headers).status_code, 204)
        self.assertEqual(PublicVisit.objects.get().ip_address, "2001:db8::1")

    def test_user_agent_is_compacted_and_truncated(self):
        ua = "A" * 513
        headers = self.signed_headers(user_agent=ua)
        self.assertEqual(self.post_signed(headers).status_code, 204)
        visit = PublicVisit.objects.get()
        self.assertLessEqual(len(visit.user_agent), 512)
        self.assertNotRegex(visit.user_agent, r"\s{2,}")

    @override_settings(VISIT_FORWARDING_SECRET="")
    def test_missing_secret_returns_503(self):
        self.assertEqual(self.client.post("/api/v1/internal/public-visits/", {}).status_code, 503)

    def test_rate_limit_allows_30_and_rejects_31_with_isolated_cache(self):
        for _ in range(30):
            self.assertEqual(self.post_signed(self.signed_headers()).status_code, 204)
        self.assertEqual(self.post_signed(self.signed_headers()).status_code, 429)
        cache.clear()
        self.assertEqual(self.post_signed(self.signed_headers()).status_code, 204)

    def test_cleanup_expired_visits_cascades_events_and_command(self):
        old = PublicVisit.objects.create(path="/", ip_address="203.0.113.2", visit_date=timezone.now().date() - timedelta(days=31), first_seen_at=timezone.now(), last_seen_at=timezone.now())
        PublicVisitEvent.objects.create(event_id=uuid.uuid4(), visit=old, path="/", ip_address=old.ip_address)
        call_command("cleanup_public_visits")
        self.assertFalse(PublicVisit.objects.filter(pk=old.pk).exists())
        self.assertFalse(PublicVisitEvent.objects.filter(visit_id=old.pk).exists())

    def test_public_visits_only_allow_post(self):
        for method in ("get", "put", "patch", "delete"):
            with self.subTest(method=method):
                response = getattr(self.client, method)("/api/v1/internal/public-visits/")
                self.assertEqual(response.status_code, status.HTTP_405_METHOD_NOT_ALLOWED)


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

    def promote(self, key, destination_key=None):
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
        self.assertEqual(request.data, b"{}")

    @patch("posts.storage.urlopen")
    def test_signed_read_posts_asset_uses_exact_request_and_normalizes_relative_url(self, urlopen_mock):
        urlopen_mock.return_value = FakeHTTPResponse(
            json.dumps({"signedURL": "/storage/v1/object/sign/private/posts/a/asset?token=abc"}).encode()
        )

        result = self.storage.create_signed_read_url("posts/a/asset", 300)

        self.assertEqual(
            result,
            "https://project.test/storage/v1/object/sign/private/posts/a/asset?token=abc",
        )
        request = urlopen_mock.call_args.args[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(
            request.full_url,
            "https://project.test/storage/v1/object/sign/private/posts/a/asset",
        )
        self.assertEqual(json.loads(request.data), {"expiresIn": 300})
        self.assertNotIn("not-a-real-secret", request.full_url)
        self.assertNotIn("not-a-real-secret", result)

    @patch("posts.storage.urlopen")
    def test_signed_read_url_preserves_absolute_url_without_leaking_secret(self, urlopen_mock):
        signed_url = "https://cdn.test/storage/v1/object/sign/private/posts/a/asset?token=abc"
        urlopen_mock.return_value = FakeHTTPResponse(json.dumps({"signedURL": signed_url}).encode())

        result = self.storage.create_signed_read_url("posts/a/asset", 300)

        self.assertEqual(result, signed_url)
        self.assertNotIn("not-a-real-secret", result)

    @patch("posts.storage.urlopen")
    def test_signed_read_url_normalizes_object_sign_relative_url_without_duplicate_prefix(self, urlopen_mock):
        urlopen_mock.return_value = FakeHTTPResponse(
            json.dumps({"signedURL": "/object/sign/private/posts/a/asset?token=abc"}).encode()
        )

        result = self.storage.create_signed_read_url("posts/a/asset", 300)

        self.assertEqual(
            result,
            "https://project.test/storage/v1/object/sign/private/posts/a/asset?token=abc",
        )
        self.assertEqual(result.count("/storage/v1"), 1)
        self.assertNotIn("not-a-real-secret", result)

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
        self.assertEqual(request.full_url, "https://project.test/storage/v1/object/public")
        self.assertEqual(json.loads(request.data), {"prefixes": ["posts/a/asset"]})
        self.assertNotIn("not-a-real-secret", request.full_url)
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

    def test_read_urls_returns_only_ready_positioned_media_in_position_order(self):
        first = self.ready_media(position=1, private_object_key="first")
        second = self.ready_media(position=0, private_object_key="second")
        self.ready_media(state=PostMedia.State.PENDING, position=2, private_object_key="pending")
        self.ready_media(position=None, private_object_key="unpositioned")
        signed = {
            "first": "https://signed.test/first",
            "second": "https://signed.test/second",
        }
        with patch.object(self.storage, "create_signed_read_url", create=True, side_effect=lambda key, ttl: signed[key]) as signer:
            with patch("posts.views.get_storage", return_value=self.storage):
                response = self.client.get(f"/api/v1/posts/{self.post.id}/media/read-urls/")

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual([item["id"] for item in response.data["media"]], [second.id, first.id])
        self.assertEqual([item["url"] for item in response.data["media"]], [signed["second"], signed["first"]])
        self.assertIn("expires_at", response.data)
        self.assertEqual(signer.call_count, 2)
        self.assertNotIn("private_object_key", response.data)
        self.assertNotIn("private_poster_key", response.data)

    def test_read_urls_hides_other_and_missing_posts(self):
        other_post = create_post(self.other, "Ajeno read")
        for post_id in (other_post.id, 999999):
            with patch("posts.views.get_storage", side_effect=AssertionError("storage must not be called")):
                response = self.client.get(f"/api/v1/posts/{post_id}/media/read-urls/")
            self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)

    def test_read_urls_storage_error_is_safe_503(self):
        with patch("posts.views.get_storage", side_effect=StorageError("secret-key-token")):
            response = self.client.get(f"/api/v1/posts/{self.post.id}/media/read-urls/")
        self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
        self.assertNotIn("secret-key-token", response.content.decode())
        self.assertNotIn("token", response.content.decode().lower())

    def test_read_urls_includes_video_poster_url(self):
        video = self.ready_media(
            kind=PostMedia.Kind.VIDEO,
            position=0,
            private_object_key="video",
            private_poster_key="poster",
        )
        with patch.object(
            self.storage,
            "create_signed_read_url",
            create=True,
            side_effect=lambda key, ttl: f"https://signed.test/{key}",
        ), patch("posts.views.get_storage", return_value=self.storage):
            response = self.client.get(f"/api/v1/posts/{self.post.id}/media/read-urls/")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["media"][0], {
            "id": video.id,
            "url": "https://signed.test/video",
            "poster_url": "https://signed.test/poster",
        })

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
        self.assertNotEqual(media.public_object_key, media.private_object_key)
        self.assertTrue(media.public_object_key.startswith("publications/"))
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
        self.assertTrue(replacement.public_object_key.startswith("publications/"))


    def test_republication_uses_fresh_keys_and_old_cleanup_preserves_new_media(self):
        media = self.ready_media(position=0, is_cover=True)
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            published = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"})
            self.assertEqual(published.status_code, 200)
            media.refresh_from_db()
            old_key = media.public_object_key
            first_date = published.data["published_at"]
            self.assertEqual(self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "draft"}).status_code, 200)
            republished = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"})
            media.refresh_from_db()
            new_key = media.public_object_key
            self.assertNotEqual(old_key, new_key)
            self.assertEqual(republished.data["published_at"], first_date)
            call_command("cleanup_media_storage")
            self.assertIn(("public", old_key), self.storage.deleted)
            self.assertNotIn(("public", new_key), self.storage.deleted)
            # Repeating the same published layout is idempotent.
            layout = {"items": [{"id": str(media.id), "position": 0, "is_cover": True}]}
            self.assertEqual(self.client.put(f"/api/v1/posts/{self.post.id}/media/layout/", layout, format="json").status_code, 200)
            media.refresh_from_db()
            self.assertEqual(media.public_object_key, new_key)
            self.assertEqual(len(self.storage.promoted), 2)

    def test_metadata_edit_after_unpublish_does_not_restore_stale_public_state(self):
        from .views import MediaDetailView
        media = self.ready_media(position=0, is_cover=True, public_object_key="retired")
        self.post.status = Post.Status.PUBLISHED
        self.post.save()
        stale_post = Post.objects.get(pk=self.post.pk)
        stale_media = PostMedia.objects.get(pk=media.pk)
        self.assertEqual(self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "draft"}).status_code, 200)
        # Simulate the stale pair a previous unlocked request had obtained.
        # Metadata edits must read fresh DB state in their transaction instead.
        with patch.object(MediaDetailView, "get_media", return_value=(stale_post, stale_media)), patch("posts.signals._send_webhook") as webhook:
            with self.captureOnCommitCallbacks(execute=True):
                response = self.client.patch(f"/api/v1/posts/{self.post.id}/media/{media.id}/", {"caption": "caption corregido"})
        self.assertEqual(response.status_code, 200)
        self.post.refresh_from_db()
        media.refresh_from_db()
        self.assertEqual(self.post.status, Post.Status.DRAFT)
        self.assertIsNone(media.public_object_key)
        self.assertEqual(media.caption, "caption corregido")
        webhook.assert_not_called()

    def test_republication_with_executed_commit_callbacks(self):
        media = self.ready_media(position=0, is_cover=True)
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage:
            with self.captureOnCommitCallbacks(execute=True):
                self.assertEqual(self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"}).status_code, 200)
            media.refresh_from_db()
            retired_key = media.public_object_key
            with self.captureOnCommitCallbacks(execute=True):
                self.assertEqual(self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "draft"}).status_code, 200)
            self.assertIn(("public", retired_key), self.storage.deleted)
            with self.captureOnCommitCallbacks(execute=True):
                self.assertEqual(self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"}).status_code, 200)
            media.refresh_from_db()
            self.assertNotEqual(media.public_object_key, retired_key)
            self.assertNotIn(("public", media.public_object_key), self.storage.deleted)
            self.assertIsNotNone(StorageDeletionTask.objects.get(object_key=retired_key).completed_at)

    def test_stale_deletion_task_never_deletes_current_public_or_private_key(self):
        media = self.ready_media(position=0, is_cover=True, public_object_key="still-public")
        StorageDeletionTask.objects.create(bucket="public", object_key=media.public_object_key)
        StorageDeletionTask.objects.create(bucket="private", object_key=media.private_object_key)
        with patch("posts.services.get_storage", return_value=self.storage):
            call_command("cleanup_media_storage")
        self.assertEqual(self.storage.deleted, [])

    def test_partial_promotion_timeout_rolls_back_layout_and_queues_all_attempts(self):
        media = self.ready_media(position=0, is_cover=True, private_poster_key="poster")
        view_storage, service_storage = self.storage_patches()
        with view_storage, service_storage, patch.object(self.storage, "promote", side_effect=[None, StorageError("timeout")]):
            response = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "published"})
        self.assertEqual(response.status_code, 503)
        self.post.refresh_from_db()
        media.refresh_from_db()
        self.assertEqual(self.post.status, Post.Status.DRAFT)
        self.assertIsNone(media.public_object_key)
        # Includes uncertain timeout outcome, not only acknowledged copies.
        self.assertEqual(StorageDeletionTask.objects.count(), 2)

    def test_cleanup_runs_after_commit_and_failure_is_durable(self):
        media = self.ready_media(position=0, is_cover=True, public_object_key="retired")
        self.post.status = Post.Status.PUBLISHED
        self.post.save()
        with patch("posts.services.get_storage", return_value=self.storage), patch.object(self.storage, "delete", side_effect=StorageError("timeout")) as delete:
            with self.captureOnCommitCallbacks(execute=True):
                response = self.client.patch(f"/api/v1/posts/{self.post.id}/", {"status": "draft"})
                delete.assert_not_called()
            self.assertEqual(response.status_code, 200)
            delete.assert_called_once_with("public", "retired")
        task = StorageDeletionTask.objects.get(object_key="retired")
        self.assertEqual(task.attempts, 1)
        self.assertIsNone(task.completed_at)
        self.assertTrue(task.last_error)
        with patch("posts.services.get_storage", return_value=self.storage):
            call_command("cleanup_media_storage")
        task.refresh_from_db()
        self.assertIsNotNone(task.completed_at)


class StabilizationTests(APITestCase):
    def setUp(self):
        cache.clear()

    def test_slug_collision_at_max_length_reserves_suffix(self):
        user = User.objects.create_user("slug-owner")
        title = "x" * 200
        first = create_post(user, title)
        second = create_post(user, title)
        self.assertEqual(len(first.slug), 200)
        self.assertEqual(len(second.slug), 200)
        self.assertTrue(second.slug.endswith("-2"))
        self.assertNotEqual(first.slug, second.slug)

    def test_login_and_refresh_have_independent_ip_limits(self):
        with patch("posts.throttles.LoginThrottle.get_rate", return_value="2/min"):
            for _ in range(2):
                self.assertEqual(self.client.post("/api/v1/auth/login/", {"username": "unknown", "password": "bad"}).status_code, 401)
            blocked = self.client.post("/api/v1/auth/login/", {"username": "unknown", "password": "bad"})
            self.assertEqual(blocked.status_code, 429)
            self.assertIn("Retry-After", blocked)
            self.assertEqual(self.client.post("/api/v1/auth/refresh/", {"refresh": "invalid"}).status_code, 401)
            self.assertEqual(self.client.post("/api/v1/auth/refresh/", {"refresh": "invalid"}).status_code, 401)
            self.assertEqual(self.client.post("/api/v1/auth/refresh/", {"refresh": "invalid"}).status_code, 429)

    @patch("posts.storage.urlopen", side_effect=TimeoutError("provider timeout"))
    def test_storage_timeout_normalized_for_all_operations(self, urlopen_mock):
        storage = SupabaseStorage("https://storage.test", "fake", "private", "public")
        operations = [lambda: storage.get_object_info("private", "key"), lambda: storage.promote("key", "new-key"), lambda: storage.delete("public", "key"), lambda: storage.create_upload_url("key", 900)]
        for operation in operations:
            with self.assertRaises(StorageError):
                operation()

    @patch("posts.storage.urlopen")
    def test_storage_promotion_copies_to_distinct_public_key(self, urlopen_mock):
        urlopen_mock.return_value = FakeHTTPResponse()
        storage = SupabaseStorage("https://storage.test", "fake", "private", "public")
        storage.promote("private-source", destination_key="new-public-key")
        payload = json.loads(urlopen_mock.call_args.args[0].data)
        self.assertEqual(payload["sourceKey"], "private-source")
        self.assertEqual(payload["destinationKey"], "new-public-key")


    def test_readiness_checks_database_without_authentication(self):
        response = self.client.get("/api/v1/ready/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, {"status": "ready"})

    def test_readiness_returns_safe_503_on_database_failure(self):
        from django.db import DatabaseError
        with patch("posts.views.connection.cursor", side_effect=DatabaseError("password=secret")):
            response = self.client.get("/api/v1/ready/")
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.data, {"status": "unavailable"})
            self.assertEqual(self.client.get("/api/v1/health/").status_code, 200)

    def test_cleanup_callbacks_are_coalesced_and_discarded_on_rollback(self):
        from django.db import transaction
        from .services import enqueue_storage_deletion
        with patch("posts.services.run_media_housekeeping") as cleanup:
            with self.captureOnCommitCallbacks(execute=True) as callbacks:
                with transaction.atomic():
                    for number in range(10):
                        enqueue_storage_deletion("public", f"retired-{number}")
            self.assertEqual(len(callbacks), 1)
            cleanup.assert_called_once_with(expire_intents=False)
            cleanup.reset_mock()
            with self.captureOnCommitCallbacks(execute=True) as rolled_back:
                try:
                    with transaction.atomic():
                        enqueue_storage_deletion("public", "rollback-key")
                        raise ValueError("rollback")
                except ValueError:
                    pass
            self.assertEqual(rolled_back, [])
            cleanup.assert_not_called()


    def test_admin_cannot_change_storage_state_or_post_status(self):
        from django.contrib import admin
        from .admin import PostAdmin, PostMediaAdmin, StorageDeletionTaskAdmin
        self.assertIn("status", PostAdmin(Post, admin.site).readonly_fields)
        for model, admin_class in [(PostMedia, PostMediaAdmin), (StorageDeletionTask, StorageDeletionTaskAdmin)]:
            model_admin = admin_class(model, admin.site)
            self.assertFalse(model_admin.has_add_permission(None))
            self.assertFalse(model_admin.has_change_permission(None))
            self.assertFalse(model_admin.has_delete_permission(None))


    def test_untrusted_forwarded_address_cannot_bypass_login_limit(self):
        with patch("posts.throttles.LoginThrottle.get_rate", return_value="1/min"):
            self.assertEqual(self.client.post("/api/v1/auth/login/", {"username": "unknown", "password": "bad"}, HTTP_X_FORWARDED_FOR="192.0.2.1").status_code, 401)
            self.assertEqual(self.client.post("/api/v1/auth/login/", {"username": "unknown", "password": "bad"}, HTTP_X_FORWARDED_FOR="192.0.2.2").status_code, 429)


class MediaBackupTests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user("backup-owner")
        self.post = create_post(self.user, "Backup")
        self.storage = FakeStorage()

    def media(self, **kwargs):
        return PostMedia.objects.create(post=self.post, kind="image", mime_type="image/jpeg", size_bytes=3, **kwargs)

    def test_backup_deduplicates_all_referenced_objects_with_private_manifest(self):
        import tempfile
        from pathlib import Path
        self.media(private_object_key="same", private_poster_key="poster", public_object_key="public-copy", public_poster_key="poster-copy")
        self.media(private_object_key="same")
        data = b"image-bytes"
        def download(bucket, key, stream, max_bytes):
            stream.write(data)
            return len(data), hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary, patch("posts.management.commands.backup_media_storage.get_storage", return_value=self.storage), patch.object(self.storage, "download_to", create=True, side_effect=download) as downloads:
            output = Path(temporary) / "backup"
            call_command("backup_media_storage", output=str(output), stdout=io.StringIO())
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertTrue(manifest["complete"])
            self.assertEqual(downloads.call_count, 4)
            self.assertEqual(len(manifest["objects"]), 4)
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)
            self.assertEqual((output / "manifest.json").stat().st_mode & 0o777, 0o600)
            for entry in manifest["objects"]:
                saved = output / entry["file"]
                self.assertEqual(saved.read_bytes(), data)
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
                self.assertEqual(saved.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("service_role", json.dumps(manifest))
            self.assertEqual(list(Path(temporary).glob("*.partial-*")), [])
            from django.core.management.base import CommandError
            with self.assertRaises(CommandError):
                call_command("backup_media_storage", output=str(output))

    def test_failed_backup_preserves_incomplete_manifest_and_safe_error(self):
        import tempfile
        from pathlib import Path
        from django.core.management.base import CommandError
        self.media(private_object_key="missing")
        with tempfile.TemporaryDirectory() as temporary, patch("posts.management.commands.backup_media_storage.get_storage", return_value=self.storage), patch.object(self.storage, "download_to", create=True, side_effect=StorageError("SECRET_TOKEN must never reach logs")):
            output = Path(temporary) / "backup"
            with self.assertRaises(CommandError) as error:
                call_command("backup_media_storage", output=str(output), stdout=io.StringIO())
            self.assertNotIn("SECRET_TOKEN", str(error.exception))
            self.assertFalse(output.exists())
            partial = list(Path(temporary).glob("backup.partial-*"))
            self.assertEqual(len(partial), 1)
            manifest = json.loads((partial[0] / "manifest.json").read_text())
            self.assertFalse(manifest["complete"])
            self.assertNotIn("SECRET_TOKEN", json.dumps(manifest))

    @patch("posts.storage.urlopen")
    def test_download_stream_enforces_limit_hash_and_rejects_path_traversal(self, urlopen_mock):
        storage = SupabaseStorage("https://storage.test", "secret", "private", "public")
        output = io.BytesIO()
        urlopen_mock.return_value = io.BytesIO(b"content")
        size, digest = storage.download_to("private", "posts/asset", output, 100)
        self.assertEqual((size, digest), (7, hashlib.sha256(b"content").hexdigest()))
        self.assertEqual(output.getvalue(), b"content")
        request = urlopen_mock.call_args.args[0]
        self.assertNotIn("secret", request.full_url)
        self.assertEqual(request.get_method(), "GET")
        urlopen_mock.return_value = io.BytesIO(b"too-large")
        with self.assertRaises(StorageError):
            storage.download_to("private", "posts/asset", io.BytesIO(), 2)
        with self.assertRaises(StorageError):
            storage.download_to("private", "../elsewhere", io.BytesIO(), 100)

    def test_backup_total_limit_keeps_recoverable_completed_objects(self):
        import tempfile
        from pathlib import Path
        from django.core.management.base import CommandError
        self.media(private_object_key="first", public_object_key="second")
        def download(bucket, key, stream, max_bytes):
            if max_bytes < 3:
                raise StorageError("limit")
            stream.write(b"123")
            return 3, hashlib.sha256(b"123").hexdigest()
        with tempfile.TemporaryDirectory() as temporary, patch("posts.management.commands.backup_media_storage.get_storage", return_value=self.storage), patch.object(self.storage, "download_to", create=True, side_effect=download):
            with self.assertRaises(CommandError):
                call_command("backup_media_storage", output=str(Path(temporary) / "backup"), max_total_bytes=4, stdout=io.StringIO())
            partial = next(Path(temporary).glob("backup.partial-*"))
            manifest = json.loads((partial / "manifest.json").read_text())
            self.assertFalse(manifest["complete"])
            self.assertEqual(len(manifest["objects"]), 1)
            self.assertEqual((partial / manifest["objects"][0]["file"]).read_bytes(), b"123")


from unittest import skipUnless
from django.db import connection as test_connection, DatabaseError as TestDatabaseError, transaction as test_transaction


@skipUnless(test_connection.vendor == "postgresql", "Permission integration requires PostgreSQL")
class SecureDataAPIIntegrationTests(APITestCase):
    def setUp(self):
        from django.core.management.base import CommandError
        self.CommandError = CommandError
        with test_connection.cursor() as cursor:
            for role in ("anon", "authenticated", "service_role"):
                cursor.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", [role])
                if cursor.fetchone() is None:
                    cursor.execute(f'CREATE ROLE "{role}" NOLOGIN')
            cursor.execute("GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role")
            cursor.execute("GRANT ALL ON TABLE public.auth_user, public.posts_post TO anon, authenticated, PUBLIC, service_role")
            cursor.execute("GRANT ALL ON SEQUENCE public.posts_post_id_seq TO anon, authenticated, PUBLIC, service_role")
            cursor.execute("CREATE TABLE public.moon_external_acl_test (id serial PRIMARY KEY)")
            cursor.execute("GRANT ALL ON public.moon_external_acl_test TO anon, authenticated, PUBLIC")
            cursor.execute("CREATE SCHEMA IF NOT EXISTS storage")
            cursor.execute("CREATE TABLE storage.moon_acl_test (id integer)")
            cursor.execute("GRANT ALL ON storage.moon_acl_test TO anon, authenticated, PUBLIC")

    def temporary_plan(self):
        import tempfile
        # /tmp is outside the MoonBlogger source tree; plan mode remains 0600.
        return tempfile.TemporaryDirectory(prefix="moon-acl-test-")

    def permission(self, role, table, permission="SELECT"):
        with test_connection.cursor() as cursor:
            cursor.execute("SELECT has_table_privilege(%s, %s, %s)", [role, table, permission])
            return cursor.fetchone()[0]

    def test_preview_preserves_grants_and_plan_scopes_exact_relations(self):
        from pathlib import Path
        with self.temporary_plan() as temporary:
            path = Path(temporary) / "plan.json"
            call_command("secure_data_api", plan_file=str(path), stdout=io.StringIO())
            plan = json.loads(path.read_text())
            self.assertTrue(self.permission("anon", "public.auth_user"))
            names = {item["name"] for item in plan["relations"]}
            self.assertIn("auth_user", names)
            self.assertIn("auth_user_groups", names)
            self.assertIn("django_migrations", names)
            self.assertIn("posts_post_id_seq", names)
            self.assertNotIn("moon_external_acl_test", names)
            self.assertNotIn("moon_external_acl_test_id_seq", names)
            self.assertNotIn("moon_acl_test", names)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_apply_denies_browser_reads_writes_preserves_owner_service_and_other_apps(self):
        from pathlib import Path
        with self.temporary_plan() as temporary:
            call_command("secure_data_api", apply=True, plan_file=str(Path(temporary) / "plan.json"), stdout=io.StringIO())
        for role in ("anon", "authenticated"):
            self.assertFalse(self.permission(role, "public.auth_user"))
            self.assertFalse(self.permission(role, "public.posts_post", "INSERT"))
            with self.assertRaises(TestDatabaseError):
                with test_transaction.atomic(), test_connection.cursor() as cursor:
                    cursor.execute(f'SET LOCAL ROLE "{role}"')
                    cursor.execute("SELECT * FROM public.auth_user LIMIT 1")
            with self.assertRaises(TestDatabaseError):
                with test_transaction.atomic(), test_connection.cursor() as cursor:
                    cursor.execute(f'SET LOCAL ROLE "{role}"')
                    cursor.execute("UPDATE public.posts_post SET title = 'unauthorized'")
        owner = User.objects.create_user("owner-after-revoke")
        post = create_post(owner, "owner can create")
        post.title = "owner can update"
        post.save()
        self.assertEqual(Post.objects.get(pk=post.pk).title, "owner can update")
        post.delete()
        self.assertFalse(Post.objects.filter(pk=post.pk).exists())
        self.assertTrue(self.permission("service_role", "public.auth_user"))
        self.assertTrue(self.permission("anon", "public.moon_external_acl_test"))
        self.assertTrue(self.permission("anon", "storage.moon_acl_test"))

    def test_inherited_browser_access_aborts_all_revocations(self):
        from pathlib import Path
        with test_connection.cursor() as cursor:
            cursor.execute("CREATE ROLE moon_acl_inherited_test NOLOGIN")
            cursor.execute("GRANT SELECT ON public.auth_user TO moon_acl_inherited_test")
            cursor.execute("GRANT moon_acl_inherited_test TO anon")
        with self.temporary_plan() as temporary:
            with self.assertRaises(self.CommandError):
                call_command("secure_data_api", apply=True, plan_file=str(Path(temporary) / "plan.json"), stdout=io.StringIO())
        self.assertTrue(self.permission("authenticated", "public.auth_user"))
        self.assertTrue(self.permission("authenticated", "public.posts_post", "INSERT"))

    def test_service_role_public_dependency_aborts_without_regranting(self):
        from pathlib import Path
        with test_connection.cursor() as cursor:
            cursor.execute("REVOKE ALL ON public.auth_user FROM service_role")
        self.assertTrue(self.permission("service_role", "public.auth_user"))
        with self.temporary_plan() as temporary:
            with self.assertRaises(self.CommandError):
                call_command("secure_data_api", apply=True, plan_file=str(Path(temporary) / "plan.json"), stdout=io.StringIO())
        self.assertTrue(self.permission("anon", "public.auth_user"))
        self.assertTrue(self.permission("service_role", "public.auth_user"))

    def test_column_acl_aborts_before_change(self):
        from pathlib import Path
        with test_connection.cursor() as cursor:
            cursor.execute("GRANT SELECT (username) ON public.auth_user TO anon")
        with self.temporary_plan() as temporary:
            with self.assertRaises(self.CommandError):
                call_command("secure_data_api", apply=True, plan_file=str(Path(temporary) / "plan.json"), stdout=io.StringIO())
        self.assertTrue(self.permission("authenticated", "public.auth_user"))

    def test_rollback_restores_original_role_privileges_and_grant_option(self):
        from pathlib import Path
        from posts.management.commands.secure_data_api import Command
        with test_connection.cursor() as cursor:
            cursor.execute("GRANT SELECT ON public.auth_user TO anon WITH GRANT OPTION")
        with self.temporary_plan() as temporary:
            path = Path(temporary) / "plan.json"
            call_command("secure_data_api", apply=True, plan_file=str(path), stdout=io.StringIO())
            plan = json.loads(path.read_text())
            with test_connection.cursor() as cursor:
                # The test owns the surrounding transaction; omit BEGIN/COMMIT.
                for sql in plan["rollback_sql"][1:-1]:
                    cursor.execute(sql)
                restored = Command().build_plan(cursor)
        for before, after in zip(plan["relations"], restored["relations"]):
            self.assertEqual(before["acl_before"], after["acl_before"])
        self.assertTrue(self.permission("anon", "public.auth_user"))


class SecureDataAPIPreviewTests(APITestCase):
    def test_non_postgresql_preview_is_safe_and_apply_refuses(self):
        from django.core.management.base import CommandError
        output = io.StringIO()
        with patch("posts.management.commands.secure_data_api.connection.vendor", "sqlite"):
            call_command("secure_data_api", stdout=output)
            self.assertIn("ningún permiso modificado", output.getvalue())
            with self.assertRaises(CommandError):
                call_command("secure_data_api", apply=True)

    def test_plan_rejects_source_repo_and_other_real_git_repositories(self):
        import tempfile
        from pathlib import Path
        from django.core.management.base import CommandError
        from posts.management.commands.secure_data_api import Command
        with self.assertRaises(CommandError):
            Command().save_plan(str(Path(settings.BASE_DIR) / "acl.json"), {})
        with tempfile.TemporaryDirectory(prefix="moon-acl-repo-test-") as temporary:
            repository = Path(temporary)
            (repository / ".git").mkdir()
            (repository / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
            with self.assertRaises(CommandError):
                Command().save_plan(str(repository / "acl.json"), {})
            self.assertFalse((repository / "acl.json").exists())
