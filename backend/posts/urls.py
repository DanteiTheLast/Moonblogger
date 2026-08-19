from django.urls import include, path
from rest_framework.routers import DefaultRouter
from rest_framework_simplejwt.views import TokenObtainPairView, TokenRefreshView

from .views import (
    CompleteMediaView,
    HealthCheckView,
    MediaDetailView,
    MediaLayoutView,
    MediaReadURLsView,
    PostViewSet,
    PublicPostViewSet,
    UploadIntentView,
    PublicVisitView,
)

router = DefaultRouter()
router.register("posts", PostViewSet, basename="post")
router.register("public/posts", PublicPostViewSet, basename="public-post")

urlpatterns = [
    path("health/", HealthCheckView.as_view(), name="health"),
    path("internal/public-visits/", PublicVisitView.as_view(), name="public-visits"),
    path("auth/login/", TokenObtainPairView.as_view(), name="token_obtain_pair"),
    path("auth/refresh/", TokenRefreshView.as_view(), name="token_refresh"),
    path("posts/<int:post_id>/media/upload-intents/", UploadIntentView.as_view(), name="media-upload-intents"),
    path("posts/<int:post_id>/media/complete/", CompleteMediaView.as_view(), name="media-complete"),
    path("posts/<int:post_id>/media/layout/", MediaLayoutView.as_view(), name="media-layout"),
    path("posts/<int:post_id>/media/read-urls/", MediaReadURLsView.as_view(), name="media-read-urls"),
    path("posts/<int:post_id>/media/<uuid:media_id>/", MediaDetailView.as_view(), name="media-detail"),
    path("", include(router.urls)),
]
