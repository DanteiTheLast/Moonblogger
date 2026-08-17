from datetime import timedelta
import hashlib
from unittest.mock import patch

from django.contrib.auth.models import User
from rest_framework import status
from rest_framework.test import APITestCase

from .models import Post
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
