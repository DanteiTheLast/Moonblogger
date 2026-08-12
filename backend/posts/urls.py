from django.urls import include, path
from rest_framework.routers import DefaultRouter
from rest_framework_simplejwt.views import TokenObtainPairView, TokenRefreshView

from .views import HealthCheckView, PostViewSet, PublicPostViewSet

router = DefaultRouter()
router.register("posts", PostViewSet, basename="post")
router.register("public/posts", PublicPostViewSet, basename="public-post")

urlpatterns = [
    path("health/", HealthCheckView.as_view(), name="health"),
    path("auth/login/", TokenObtainPairView.as_view(), name="token_obtain_pair"),
    path("auth/refresh/", TokenRefreshView.as_view(), name="token_refresh"),
    path("", include(router.urls)),
]
