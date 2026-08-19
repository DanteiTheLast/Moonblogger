import hashlib
import hmac
import ipaddress
import re
import time
from uuid import UUID
from datetime import datetime, timedelta, timezone

from django.conf import settings
from .models import Post, PublicVisit

POST_PATH = re.compile(r"^/posts/([a-zA-Z0-9][a-zA-Z0-9_-]*)$")

def sanitize_user_agent(value):
    value = " ".join(str(value or "").split())
    return value[:512]

def valid_public_path(path):
    if path == "/":
        return True
    match = POST_PATH.fullmatch(path or "")
    return bool(match and Post.objects.filter(slug=match.group(1), status=Post.Status.PUBLISHED).exists())

def canonical_ip(value):
    try:
        return str(ipaddress.ip_address(str(value).strip()))
    except (ValueError, TypeError):
        return None

def canonical_event_id(value):
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        return None

def verified_visitor_ip(request, timestamp, event_id, path, user_agent):
    secret = getattr(settings, "VISIT_FORWARDING_SECRET", "")
    supplied = request.headers.get("X-Visitor-Signature", "")
    forwarded = request.headers.get("X-Visitor-IP", "")
    if not secret or not timestamp or not supplied or not forwarded:
        return None
    try:
        ts = int(timestamp)
        ip = canonical_ip(forwarded)
        if not ip or not canonical_event_id(event_id):
            return None
    except (ValueError, TypeError):
        return None
    if abs(time.time() - ts) > settings.VISIT_FORWARDING_MAX_AGE_SECONDS:
        return None
    payload = f"{timestamp}\n{event_id}\n{ip}\n{path}\n{sanitize_user_agent(user_agent)}".encode()
    expected = hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest()
    return ip if hmac.compare_digest(expected, supplied.strip().lower()) else None

def utc_now():
    return datetime.now(timezone.utc)


def cleanup_expired_visits(limit):
    cutoff = utc_now().date() - timedelta(days=settings.VISIT_RETENTION_DAYS)
    ids = list(
        PublicVisit.objects.filter(visit_date__lt=cutoff)
        .order_by("visit_date", "id")
        .values_list("id", flat=True)[:limit]
    )
    if ids:
        PublicVisit.objects.filter(id__in=ids).delete()
    return len(ids)
