"""Small, replaceable Supabase Storage REST adapter (no file bytes pass Django)."""

import json
import logging
import re
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urljoin
from urllib.request import Request, urlopen

from django.conf import settings


logger = logging.getLogger(__name__)

# Error payloads are diagnostic input from an external service.  A small cap
# permits recognition of Storage's structured error codes without retaining or
# logging arbitrary response content.
MAX_ERROR_RESPONSE_BYTES = 4096
_SAFE_PROVIDER_VALUE = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


class StorageError(Exception):
    pass


class StorageUnavailable(StorageError):
    pass


class StorageObjectNotFound(StorageError):
    pass


class StorageRequestError(StorageError):
    """A safe diagnostic for an HTTP rejection while checking an object."""

    def __init__(self, http_status, provider_code=None, request_id=None):
        self.http_status = http_status
        # ``status_code`` is retained as an intuitive alias for callers that
        # need the upstream HTTP status; it is not a DRF response status.
        self.status_code = http_status
        self.provider_code = provider_code
        self.request_id = request_id
        super().__init__(
            f"Supabase Storage rechazó HEAD del objeto (HTTP {http_status})."
        )


@dataclass(frozen=True)
class ObjectInfo:
    size_bytes: int
    mime_type: str


class SupabaseStorage:
    def __init__(self, base_url, service_role_key, private_bucket, public_bucket):
        self.base_url = base_url.rstrip("/")
        self.service_role_key = service_role_key
        self.private_bucket = private_bucket
        self.public_bucket = public_bucket

    def _request(self, method, path, payload=None, operation=None, response_headers=False):
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = Request(
            f"{self.base_url}/storage/v1{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.service_role_key}",
                "apikey": self.service_role_key,
                "Content-Type": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=10) as response:
                if response_headers:
                    # HEAD is the object-existence endpoint.  Its useful
                    # metadata is in the response headers; do not attempt to
                    # read or parse an object response body.
                    return response.headers
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            error_data = self._read_error_data(exc)
            provider_code = self._safe_provider_value(error_data.get("code"))
            request_id = self._request_id(exc)
            if operation == "object_info":
                logger.warning(
                    "Supabase Storage request rejected operation=object_info status=%s "
                    "provider_code=%s request_id=%s",
                    exc.code,
                    provider_code or "-",
                    request_id or "-",
                )
            if self._is_not_found_error(exc.code, error_data):
                raise StorageObjectNotFound("El objeto de Storage no existe.") from exc
            if operation == "object_info":
                raise StorageRequestError(exc.code, provider_code, request_id) from exc
            raise StorageError("Supabase Storage no pudo completar la operación.") from exc
        except URLError as exc:
            if operation == "object_info":
                raise StorageError(
                    "Supabase Storage no está disponible para verificar el objeto."
                ) from exc
            raise StorageError("Supabase Storage no pudo completar la operación.") from exc
        except ValueError as exc:
            raise StorageError("Supabase Storage no pudo completar la operación.") from exc

    @staticmethod
    def _read_error_data(error):
        """Read a bounded HTTP error response exactly once and parse JSON safely."""
        try:
            raw = error.read(MAX_ERROR_RESPONSE_BYTES)
            if not isinstance(raw, bytes):
                return {}
            data = json.loads(raw.decode("utf-8"))
        except (AttributeError, TypeError, UnicodeDecodeError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}

    @staticmethod
    def _safe_provider_value(value):
        if isinstance(value, str) and _SAFE_PROVIDER_VALUE.fullmatch(value):
            return value
        return None

    @classmethod
    def _request_id(cls, error):
        headers = getattr(error, "headers", None)
        if not headers:
            return None
        try:
            header_items = headers.items()
        except AttributeError:
            return None
        for name, value in header_items:
            if str(name).lower() in {"x-request-id", "x-supabase-request-id"}:
                return cls._safe_provider_value(value)
        return None

    @staticmethod
    def _is_not_found_error(status_code, error_data):
        if status_code == 404:
            return True
        if status_code != 400:
            return False
        return error_data.get("code") == "NoSuchKey" or error_data.get("statusCode") == "404"

    def create_upload_url(self, object_key, expires_in):
        # Supabase's signed-upload endpoint requires a JSON request body.  The
        # endpoint has its own fixed validity period; ``expires_in`` remains an
        # application-only deadline for completing and cleaning up the intent.
        data = self._request(
            "POST",
            f"/object/upload/sign/{quote(self.private_bucket, safe='')}/{quote(object_key, safe='/')}",
            {},
        )
        signed_url = data.get("url") or data.get("signedURL") or data.get("signedUrl")
        if not signed_url:
            raise StorageError("Supabase Storage no devolvió una URL de carga.")
        return signed_url if signed_url.startswith("http") else f"{self.base_url}/storage/v1{signed_url}"

    def create_signed_read_url(self, object_key, expires_in):
        data = self._request("POST", f"/object/sign/{quote(self.private_bucket, safe='')}/{quote(object_key, safe='/')}", {"expiresIn": expires_in}, operation="signed_read_url")
        signed_url = data.get("signedURL") or data.get("signedUrl") or data.get("url")
        if not isinstance(signed_url, str) or not signed_url:
            raise StorageError("Supabase Storage no devolvió una URL de lectura.")
        return signed_url if signed_url.startswith("http") else urljoin(f"{self.base_url}/", signed_url.lstrip("/"))

    def get_object_info(self, bucket, object_key):
        headers = self._request(
            "HEAD",
            f"/object/{quote(bucket, safe='')}/{quote(object_key, safe='/')}",
            operation="object_info",
            response_headers=True,
        )
        try:
            raw_size = headers.get("Content-Length")
            if isinstance(raw_size, bool) or not isinstance(raw_size, (int, str)):
                raise ValueError
            size_bytes = int(raw_size)
            mime_type = headers.get("Content-Type")
            if size_bytes < 0 or not isinstance(mime_type, str) or not mime_type.strip():
                raise ValueError
            return ObjectInfo(
                size_bytes=size_bytes,
                mime_type=mime_type.strip(),
            )
        except (AttributeError, TypeError, ValueError) as exc:
            raise StorageError("Supabase Storage devolvió encabezados de objeto inválidos.") from exc

    def promote(self, object_key):
        self._request(
            "POST",
            "/object/copy",
            {
                "bucketId": self.private_bucket,
                "sourceKey": object_key,
                "destinationBucket": self.public_bucket,
                "destinationKey": object_key,
            },
        )

    def delete(self, bucket, object_key):
        try:
            self._request(
                "DELETE",
                f"/object/{quote(bucket, safe='')}",
                {"prefixes": [object_key]},
            )
        except StorageObjectNotFound:
            # Storage deletion is idempotent: an already absent object is clean.
            return

    def public_url(self, object_key):
        return (
            f"{self.base_url}/storage/v1/object/public/"
            f"{quote(self.public_bucket, safe='')}/{quote(object_key, safe='/')}"
        )


def get_storage():
    values = (
        settings.MEDIA_STORAGE_URL,
        settings.MEDIA_STORAGE_SERVICE_ROLE_KEY,
        settings.MEDIA_STORAGE_PRIVATE_BUCKET,
        settings.MEDIA_STORAGE_PUBLIC_BUCKET,
    )
    if not all(values):
        raise StorageUnavailable(
            "El almacenamiento multimedia no está configurado. "
            "Configure las variables SUPABASE_STORAGE_* en el servidor."
        )
    return SupabaseStorage(*values)
