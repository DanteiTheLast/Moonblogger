from django.urls import include, path
from rest_framework.routers import DefaultRouter
from .throttles import ThrottledTokenObtainPairView, ThrottledTokenRefreshView

from .views import (
    CompleteMediaView,
    HealthCheckView,
    ReadinessCheckView,
    MediaDetailView,
    MediaLayoutView,
    MediaReadURLsView,
    PostViewSet,
    PublicPostViewSet,
    UploadIntentView,
    PublicVisitView,
    TagCatalogView,
    PublicTagCatalogView,
)

router = DefaultRouter()
router.register("posts", PostViewSet, basename="post")
router.register("public/posts", PublicPostViewSet, basename="public-post")

urlpatterns = [
    path("tags/", TagCatalogView.as_view(), name="tags"),
    path("public/tags/", PublicTagCatalogView.as_view(), name="public-tags"),
    path("health/", HealthCheckView.as_view(), name="health"),
    path("ready/", ReadinessCheckView.as_view(), name="ready"),
    path("internal/public-visits/", PublicVisitView.as_view(), name="public-visits"),
    path("auth/login/", ThrottledTokenObtainPairView.as_view(), name="token_obtain_pair"),
    path("auth/refresh/", ThrottledTokenRefreshView.as_view(), name="token_refresh"),
    path("posts/<int:post_id>/media/upload-intents/", UploadIntentView.as_view(), name="media-upload-intents"),
    path("posts/<int:post_id>/media/complete/", CompleteMediaView.as_view(), name="media-complete"),
    path("posts/<int:post_id>/media/layout/", MediaLayoutView.as_view(), name="media-layout"),
    path("posts/<int:post_id>/media/read-urls/", MediaReadURLsView.as_view(), name="media-read-urls"),
    path("posts/<int:post_id>/media/<uuid:media_id>/", MediaDetailView.as_view(), name="media-detail"),
    path("", include(router.urls)),
]
