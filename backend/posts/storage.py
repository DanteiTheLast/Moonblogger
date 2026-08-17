"""Small, replaceable Supabase Storage REST adapter (no file bytes pass Django)."""

import json
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from django.conf import settings


class StorageError(Exception):
    pass


class StorageUnavailable(StorageError):
    pass


class StorageObjectNotFound(StorageError):
    pass


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

    def _request(self, method, path, payload=None):
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
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            if self._is_not_found_error(exc):
                raise StorageObjectNotFound("El objeto de Storage no existe.") from exc
            raise StorageError("Supabase Storage no pudo completar la operación.") from exc
        except (URLError, ValueError) as exc:
            raise StorageError("Supabase Storage no pudo completar la operación.") from exc

    @staticmethod
    def _is_not_found_error(error):
        if error.code == 404:
            return True
        if error.code != 400:
            return False
        try:
            data = json.loads(error.read().decode("utf-8"))
        except (AttributeError, UnicodeDecodeError, ValueError):
            return False
        return data.get("code") == "NoSuchKey" or data.get("statusCode") == "404"

    def create_upload_url(self, object_key, expires_in):
        data = self._request(
            "POST",
            f"/object/upload/sign/{quote(self.private_bucket, safe='')}/{quote(object_key, safe='/')}",
        )
        signed_url = data.get("url") or data.get("signedURL") or data.get("signedUrl")
        if not signed_url:
            raise StorageError("Supabase Storage no devolvió una URL de carga.")
        return signed_url if signed_url.startswith("http") else f"{self.base_url}/storage/v1{signed_url}"

    def get_object_info(self, bucket, object_key):
        data = self._request(
            "GET",
            f"/object/info/{quote(bucket, safe='')}/{quote(object_key, safe='/')}",
        )
        try:
            raw_size = data["size"]
            if isinstance(raw_size, bool) or not isinstance(raw_size, (int, str)):
                raise ValueError
            size_bytes = int(raw_size)
            mime_type = data["content_type"]
            if size_bytes < 0 or not isinstance(mime_type, str) or not mime_type:
                raise ValueError
            return ObjectInfo(
                size_bytes=size_bytes,
                mime_type=mime_type,
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise StorageError("Supabase Storage devolvió metadatos inválidos.") from exc

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
                f"/object/{quote(bucket, safe='')}/{quote(object_key, safe='/')}",
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
