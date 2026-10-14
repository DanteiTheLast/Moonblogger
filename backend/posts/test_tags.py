from unittest.mock import patch

from django.contrib.auth.models import User
from django.db import transaction
from rest_framework.test import APITestCase
from .models import Post, Tag
from .storage import StorageError


class TagAPITests(APITestCase):
    def setUp(self):
        self.user = User.objects.create_user('moon', password='testpass')
        self.client.force_authenticate(self.user)
        self.travel = Tag.objects.get(slug='viajes')
        self.dan = Tag.objects.get(slug='dan')

    def post(self, title, tags=(), published=True):
        post = Post.objects.create(author=self.user, title=title, content='Texto',
                                   status='published' if published else 'draft')
        post.tags.set(tags)
        return post

    def test_catalog_is_seeded_and_private_requires_auth(self):
        self.assertEqual(len(self.client.get('/api/v1/tags/').data), 9)
        self.client.force_authenticate(None)
        self.assertEqual(self.client.get('/api/v1/tags/').status_code, 401)
        self.assertEqual(self.client.get('/api/v1/public/tags/').data, [])

    def test_public_catalog_never_leaks_draft_only_tags(self):
        self.post('draft', [self.dan], published=False)
        self.post('public', [self.travel])
        self.client.force_authenticate(None)
        self.assertEqual(self.client.get('/api/v1/public/tags/').data, [{'slug': 'viajes', 'name': 'Viajes'}])

    def test_create_reads_tags_and_deduplicates(self):
        response = self.client.post('/api/v1/posts/', {'title': 'Texto', 'content': 'Contenido',
                                   'tag_slugs': ['dan', 'dan', 'viajes']}, format='json')
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.data['tags'], [{'slug': 'viajes', 'name': 'Viajes'}, {'slug': 'dan', 'name': 'Dan'}])
        self.assertNotIn('tag_slugs', response.data)

    def test_maximum_unknown_and_invalid_list_do_not_create(self):
        for slugs in [['unknown'], list(Tag.objects.values_list('slug', flat=True))[:6], 'dan', [None]]:
            response = self.client.post('/api/v1/posts/', {'title': 'No', 'content': 'Texto', 'tag_slugs': slugs}, format='json')
            self.assertEqual(response.status_code, 400)
        self.assertEqual(Post.objects.count(), 0)

    def test_legacy_put_patch_preserve_and_empty_clears(self):
        post = self.post('old', [self.dan], published=False)
        endpoint = f'/api/v1/posts/{post.pk}/'
        for method, data in [(self.client.put, {'title': 'Edit', 'content': 'Changed', 'status': 'draft'}),
                             (self.client.patch, {'content': 'Another'})]:
            self.assertEqual(method(endpoint, data, format='json').status_code, 200)
            self.assertEqual(list(post.tags.values_list('slug', flat=True)), ['dan'])
        response = self.client.patch(endpoint, {'tag_slugs': []}, format='json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['tags'], [])

    def test_inactive_can_be_preserved_but_not_newly_assigned(self):
        post = self.post('old', [self.dan], published=False)
        self.dan.is_active = False
        self.dan.save()
        endpoint = f'/api/v1/posts/{post.pk}/'
        self.assertEqual(self.client.patch(endpoint, {'tag_slugs': ['dan', 'viajes']}, format='json').status_code, 200)
        self.assertEqual(self.client.post('/api/v1/posts/', {'title': 'No', 'content': 'Texto', 'tag_slugs': ['dan']}, format='json').status_code, 400)
        self.assertNotIn('dan', [tag['slug'] for tag in self.client.get('/api/v1/tags/').data])

    def test_or_unique_before_pagination_and_public_detail(self):
        both = self.post('both', [self.travel, self.dan])
        travel = self.post('travel', [self.travel])
        dan = self.post('dan', [self.dan])
        self.post('draft', [self.dan], published=False)
        self.post('other')
        self.client.force_authenticate(None)
        response = self.client.get('/api/v1/public/posts/?tag=viajes&tag=dan')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['count'], 3)
        self.assertEqual({p['id'] for p in response.data['results']}, {both.pk, travel.pk, dan.pk})
        self.assertEqual(len(response.data['results']), 3)
        self.assertEqual(len(self.client.get(f'/api/v1/public/posts/{both.slug}/').data['tags']), 2)
        self.assertEqual(self.client.get('/api/v1/public/posts/?tag=unknown').status_code, 400)
        for index in range(24): self.post(f'number{index}', [self.dan])
        first = self.client.get('/api/v1/public/posts/?tag=dan&tag=viajes')
        second = self.client.get('/api/v1/public/posts/?tag=dan&tag=viajes&page=2')
        self.assertEqual(first.data['count'], 27)
        self.assertFalse({p['id'] for p in first.data['results']} & {p['id'] for p in second.data['results']})

    def test_cross_owner_cannot_change_tags(self):
        post = self.post('owned', [self.dan])
        self.client.force_authenticate(User.objects.create_user('other'))
        self.assertEqual(self.client.patch(f'/api/v1/posts/{post.pk}/', {'tag_slugs': []}, format='json').status_code, 404)
        self.assertEqual(post.tags.count(), 1)

    @patch('posts.signals._send_webhook')
    def test_public_m2m_invalidation_on_commit_and_rollback(self, webhook):
        post = self.post('published')
        with self.captureOnCommitCallbacks(execute=True): post.tags.add(self.dan)
        webhook.assert_called_with('published', 'published')
        webhook.reset_mock()
        with self.captureOnCommitCallbacks(execute=True):
            try:
                with transaction.atomic():
                    post.tags.clear()
                    raise ValueError('rollback')
            except ValueError: pass
        webhook.assert_not_called()
        self.assertEqual(post.tags.count(), 1)
        draft = self.post('draft', published=False)
        with self.captureOnCommitCallbacks(execute=True): draft.tags.add(self.travel)
        webhook.assert_not_called()

    @patch('posts.signals._send_webhook')
    def test_public_catalog_edit_invalidation(self, webhook):
        self.post('public', [self.dan])
        with self.captureOnCommitCallbacks(execute=True):
            self.dan.name = 'Dan!'
            self.dan.save()
        webhook.assert_called_with('published', 'published')

    def test_security_command_includes_auto_created_tag_relation(self):
        from django.apps import apps
        names = {model._meta.db_table for model in apps.get_models(include_auto_created=True)
                 if model._meta.app_label == 'posts'}
        self.assertTrue({'posts_tag', 'posts_post_tags'} <= names)

    @patch('posts.views.publish_post', side_effect=StorageError('unavailable'))
    def test_publish_failure_rolls_back_tags_and_content(self, publish):
        post = self.post('old', [self.dan], published=False)
        response = self.client.patch(f'/api/v1/posts/{post.pk}/',
                                     {'title': 'New', 'status': 'published', 'tag_slugs': ['viajes']}, format='json')
        self.assertEqual(response.status_code, 503)
        post.refresh_from_db()
        self.assertEqual(post.title, 'old')
        self.assertEqual(post.status, 'draft')
        self.assertEqual(list(post.tags.values_list('slug', flat=True)), ['dan'])
