"""Back up ORM-referenced objects without bucket listings or signed URLs."""
import hashlib
import json
import os
from pathlib import Path
import uuid

from django.core.management.base import BaseCommand, CommandError
from django.db import DatabaseError
from django.utils import timezone

from posts.models import PostMedia
from posts.storage import StorageError, get_storage


class Command(BaseCommand):
    help = "Respalda objetos multimedia referenciados y un manifiesto SHA256 privado."

    def add_arguments(self, parser):
        parser.add_argument("--output", required=True, help="Directorio nuevo del respaldo; nunca se sobrescribe.")
        parser.add_argument("--max-object-bytes", type=int, default=64 * 1024 * 1024)
        parser.add_argument("--max-total-bytes", type=int, default=2 * 1024 * 1024 * 1024)

    def handle(self, *args, **options):
        max_object = options["max_object_bytes"]
        max_total = options["max_total_bytes"]
        if max_object < 1 or max_total < 1:
            raise CommandError("Los límites de bytes deben ser positivos.")
        output = Path(options["output"]).expanduser().absolute()
        if output.exists() or output.is_symlink():
            raise CommandError("El directorio de destino ya existe; elige uno nuevo.")
        if not output.parent.is_dir():
            raise CommandError("El directorio padre del destino debe existir.")
        try:
            storage = get_storage()
        except StorageError as exc:
            raise CommandError("Storage no está disponible para el respaldo.") from None
        stage = output.with_name(f"{output.name}.partial-{uuid.uuid4().hex}")
        manifest = {"version": 1, "complete": False, "started_at": timezone.now().isoformat(), "objects": []}
        total = 0
        seen = set()
        try:
            stage.mkdir(mode=0o700)
            (stage / "objects").mkdir(mode=0o700)
            self.write_manifest(stage, manifest)
            rows = PostMedia.objects.values_list(
                "private_object_key", "private_poster_key", "public_object_key", "public_poster_key"
            ).iterator(chunk_size=200)
            for row in rows:
                for bucket, key in zip((storage.private_bucket, storage.private_bucket, storage.public_bucket, storage.public_bucket), row):
                    if not key or (bucket, key) in seen:
                        continue
                    seen.add((bucket, key))
                    if total >= max_total:
                        raise StorageError("El respaldo supera el límite total de bytes.")
                    # Local names never contain provider object keys or paths.
                    filename = hashlib.sha256(json.dumps([bucket, key]).encode()).hexdigest()
                    relative = f"objects/{filename}"
                    destination = stage / relative
                    temporary = destination.with_suffix(".partial")
                    with temporary.open("xb") as stream:
                        os.chmod(temporary, 0o600)
                        size, digest = storage.download_to(bucket, key, stream, min(max_object, max_total - total))
                        stream.flush()
                        os.fsync(stream.fileno())
                    temporary.rename(destination)
                    total += size
                    manifest["objects"].append({"bucket": bucket, "object_key": key, "file": relative, "size_bytes": size, "sha256": digest})
                    self.write_manifest(stage, manifest)
            manifest.update(complete=True, finished_at=timezone.now().isoformat(), total_bytes=total)
            self.write_manifest(stage, manifest)
            if output.exists() or output.is_symlink():
                raise OSError("destination exists")
            stage.rename(output)
        except (StorageError, OSError, DatabaseError) as exc:
            manifest["complete"] = False
            manifest["error"] = "El respaldo no se completó; verifica Storage y los límites."
            if stage.is_dir():
                try:
                    self.write_manifest(stage, manifest)
                except OSError:
                    pass
            # Provider errors may include credentials; never print exception text.
            raise CommandError(f"Respaldo incompleto. Archivos recuperables en {stage}.") from None
        self.stdout.write(self.style.SUCCESS(f"Respaldo completo: {len(seen)} objetos, {total} bytes en {output}."))

    @staticmethod
    def write_manifest(stage, manifest):
        temporary = stage / "manifest.json.partial"
        with temporary.open("w", encoding="utf-8") as stream:
            os.chmod(temporary, 0o600)
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(stage / "manifest.json")
