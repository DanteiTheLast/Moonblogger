from rest_framework import permissions, viewsets
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Post
from .permissions import IsOwnerOrReadOnly
from .serializers import PostSerializer


class HealthCheckView(APIView):
    permission_classes = [permissions.AllowAny]

    def get(self, request):
        return Response({"status": "ok"})


class PostViewSet(viewsets.ModelViewSet):
    serializer_class = PostSerializer
    permission_classes = [permissions.IsAuthenticated, IsOwnerOrReadOnly]

    def get_queryset(self):
        queryset = Post.objects.select_related("author").filter(author=self.request.user)
        status_filter = self.request.query_params.get("status")
        if status_filter in Post.Status.values:
            queryset = queryset.filter(status=status_filter)
        return queryset

    def perform_create(self, serializer):
        serializer.save(author=self.request.user)


class PublicPostViewSet(viewsets.ReadOnlyModelViewSet):
    serializer_class = PostSerializer
    permission_classes = [permissions.AllowAny]
    lookup_field = "slug"

    def get_queryset(self):
        return Post.objects.filter(status=Post.Status.PUBLISHED).order_by("-published_at", "-id")
