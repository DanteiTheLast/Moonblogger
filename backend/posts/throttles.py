"""Bound authentication traffic without counting authenticated editor requests."""
from rest_framework.throttling import SimpleRateThrottle
from rest_framework_simplejwt.views import TokenObtainPairView, TokenRefreshView


class LoginThrottle(SimpleRateThrottle):
    scope = "auth_login"

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": self.get_ident(request)}


class RefreshThrottle(LoginThrottle):
    scope = "auth_refresh"


class ThrottledTokenObtainPairView(TokenObtainPairView):
    throttle_classes = [LoginThrottle]


class ThrottledTokenRefreshView(TokenRefreshView):
    throttle_classes = [RefreshThrottle]
