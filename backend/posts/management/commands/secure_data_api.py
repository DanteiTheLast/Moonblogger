"""Revoke browser-facing grants only on MoonBlogger's Django relations."""
import json
import os
from pathlib import Path

from django.apps import apps
from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from django.db import connection, transaction, DatabaseError

APP_LABELS = {"posts", "auth", "contenttypes", "sessions", "admin", "token_blacklist"}
TARGET_ROLES = ("PUBLIC", "anon", "authenticated")


class Command(BaseCommand):
    help = "Previsualiza y opcionalmente revoca acceso Data API a tablas Django (PostgreSQL)."

    def add_arguments(self, parser):
        parser.add_argument("--apply", action="store_true")
        parser.add_argument("--plan-file", help="Archivo JSON nuevo y privado fuera del proyecto; obligatorio con --apply.")

    def handle(self, *args, **options):
        if connection.vendor != "postgresql":
            if options["apply"]:
                raise CommandError("--apply requiere PostgreSQL.")
            self.stdout.write("Vista previa: PostgreSQL requerido; ningún permiso modificado.")
            return
        if options["apply"] and not options["plan_file"]:
            raise CommandError("--apply requiere --plan-file fuera del proyecto para conservar ACL y rollback.")
        try:
            with transaction.atomic():
                with connection.cursor() as cursor:
                    plan = self.build_plan(cursor)
                    plan["requested_mode"] = "apply" if options["apply"] else "preview"
                    if options["plan_file"]:
                        self.save_plan(options["plan_file"], plan)
                    for relation in plan["relations"]:
                        self.stdout.write(f'{relation["kind"]}: public.{relation["name"]}')
                    self.stdout.write(f'{len(plan["relations"])} relaciones; roles: {", ".join(plan["roles"])}.')
                    absent = [role for role in TARGET_ROLES if role not in plan["roles"]]
                    if absent:
                        self.stdout.write(f'Roles no presentes: {", ".join(absent)}; sólo se consideran los existentes.')
                    if not options["apply"]:
                        self.stdout.write("Vista previa: ningún permiso modificado.")
                        return
                    if not plan["column_acl_supported"]:
                        raise CommandError("Hay permisos de columnas que requieren revisión específica; no se cambió ningún permiso.")
                    if not plan["owner_access_confirmed"]:
                        raise CommandError("Se requiere un rol con acceso efectivo de propietario a todas las relaciones; no se cambió ningún permiso.")
                    for sql in plan["apply_sql"]:
                        cursor.execute(sql)
                    self.verify_no_browser_access(cursor, plan)
                    self.verify_preserved_access(cursor, plan)
            self.stdout.write(self.style.SUCCESS("Permisos revocados; propietario y grants ajenos conservados."))
        except DatabaseError:
            raise CommandError("No se pudo completar la operación de permisos; la transacción se revirtió. Revisa privilegios y dependencias de grants.") from None

    def build_plan(self, cursor):
        table_names = sorted({model._meta.db_table for model in apps.get_models(include_auto_created=True) if model._meta.app_label in APP_LABELS and model._meta.managed} | {"django_migrations"})
        cursor.execute("SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') ORDER BY rolname")
        roles = ["PUBLIC"] + [row[0] for row in cursor.fetchall()]
        cursor.execute("SELECT current_user, current_database(), current_setting('server_version')")
        actor, database, server_version = cursor.fetchone()
        cursor.execute("""
            SELECT c.oid, c.relname, c.relkind, pg_get_userbyid(c.relowner),
                   pg_has_role(current_user, c.relowner, 'USAGE')
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relname = ANY(%s)
            ORDER BY c.relname
        """, [table_names])
        relations = list(cursor.fetchall())
        table_oids = [row[0] for row in relations]
        cursor.execute("""
            SELECT DISTINCT s.oid, s.relname, s.relkind, pg_get_userbyid(s.relowner),
                   pg_has_role(current_user, s.relowner, 'USAGE')
            FROM pg_class s JOIN pg_namespace n ON n.oid = s.relnamespace
            JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass
            WHERE n.nspname = 'public' AND s.relkind = 'S'
              AND d.refclassid = 'pg_class'::regclass AND d.refobjid = ANY(%s)
              AND d.deptype IN ('a', 'i') ORDER BY s.relname
        """, [table_oids])
        relations += list(cursor.fetchall())
        result = {"version": 1, "actor": actor, "schema": "public", "roles": roles,
                  "database": database, "server_version": server_version,
                  "connection_target": {key: str(connection.settings_dict.get(key, "")) for key in ("HOST", "PORT", "NAME", "USER")},
                  "owner_access_confirmed": bool(relations) and all(row[4] and row[3] not in roles for row in relations),
                  "relations": [], "apply_sql": [], "rollback_sql": [], "column_acl_supported": True}
        quote = connection.ops.quote_name
        cursor.execute("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = 'service_role')")
        protected_roles = [actor] + (["service_role"] if cursor.fetchone()[0] else [])
        for oid, name, kind, owner, accessible in relations:
            sql_kind = "SEQUENCE" if kind == "S" else "TABLE"
            qualified = f'{quote("public")}.{quote(name)}'
            cursor.execute("""
                SELECT pg_get_userbyid(a.grantor), CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,
                       a.privilege_type, a.is_grantable
                FROM pg_class c, LATERAL aclexplode(COALESCE(c.relacl, acldefault(CASE WHEN c.relkind = 'S' THEN 's'::\"char\" ELSE 'r'::\"char\" END, c.relowner))) a
                WHERE c.oid = %s ORDER BY 1, 2, 3, 4
            """, [oid])
            acl = [{"grantor": g, "grantee": r, "privilege": p, "grant_option": o} for g, r, p, o in cursor.fetchall()]
            cursor.execute("SELECT relacl::text FROM pg_class WHERE oid = %s", [oid])
            raw_acl = cursor.fetchone()[0]
            cursor.execute("SELECT attname, attacl::text FROM pg_attribute WHERE attrelid = %s AND attnum > 0 AND NOT attisdropped AND attacl IS NOT NULL ORDER BY attnum", [oid])
            column_acl = [{"column": n, "acl": a} for n, a in cursor.fetchall()]
            if column_acl:
                result["column_acl_supported"] = False
            privileges = sorted({item["privilege"] for item in acl} | ({"SELECT", "UPDATE", "USAGE"} if kind == "S" else {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}))
            protected = {}
            function = "has_sequence_privilege" if kind == "S" else "has_table_privilege"
            for role in protected_roles:
                protected[role] = {}
                for privilege in privileges:
                    cursor.execute(f"SELECT {function}(%s, %s, %s)", [role, qualified, privilege])
                    protected[role][privilege] = cursor.fetchone()[0]
            result["relations"].append({"name": name, "kind": sql_kind.lower(), "owner": owner, "acl_before": acl, "raw_acl_before": raw_acl, "column_acl_before": column_acl, "protected_access_before": protected, "privileges": privileges})
            # RESTRICT (default): dependent grants must never be removed by CASCADE.
            revoke = f'REVOKE ALL PRIVILEGES ON {sql_kind} {qualified} FROM ' + ", ".join("PUBLIC" if role == "PUBLIC" else quote(role) for role in roles) + ";"
            result["apply_sql"].append(revoke)
            result["rollback_sql"].append(revoke)
            for item in acl:
                if item["grantee"] not in roles:
                    continue
                target = "PUBLIC" if item["grantee"] == "PUBLIC" else quote(item["grantee"])
                result["rollback_sql"].extend([
                    f'SET LOCAL ROLE {quote(item["grantor"])};',
                    f'GRANT {item["privilege"]} ON {sql_kind} {qualified} TO {target}' + (" WITH GRANT OPTION" if item["grant_option"] else "") + ";",
                    "RESET ROLE;",
                ])
        result["rollback_sql"] = ["BEGIN;"] + result["rollback_sql"] + ["COMMIT;"]
        return result

    def verify_no_browser_access(self, cursor, plan):
        # Inherited grants or ownership would survive direct REVOKE. Abort the
        # whole transaction rather than report a closure that did not happen.
        for relation in plan["relations"]:
            qualified = f'public.{connection.ops.quote_name(relation["name"])}'
            privileges = relation["privileges"]
            function = "has_sequence_privilege" if relation["kind"] == "sequence" else "has_table_privilege"
            cursor.execute("SELECT EXISTS(SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a WHERE c.oid = %s::regclass AND a.grantee = 0)", [qualified])
            if cursor.fetchone()[0]:
                raise CommandError("Persisten grants PUBLIC; la transacción se revirtió.")
            for role in plan["roles"]:
                if role == "PUBLIC":
                    continue
                for privilege in privileges:
                    cursor.execute(f"SELECT {function}(%s, %s, %s)", [role, qualified, privilege])
                    if cursor.fetchone()[0]:
                        raise CommandError("Un rol Data API conserva permisos heredados; la transacción se revirtió. Revisa membresías sin ampliar esta revocación.")

    def verify_preserved_access(self, cursor, plan):
        for relation in plan["relations"]:
            qualified = f'public.{connection.ops.quote_name(relation["name"])}'
            function = "has_sequence_privilege" if relation["kind"] == "sequence" else "has_table_privilege"
            for role, privileges in relation["protected_access_before"].items():
                for privilege, allowed in privileges.items():
                    cursor.execute(f"SELECT {function}(%s, %s, %s)", [role, qualified, privilege])
                    if cursor.fetchone()[0] != allowed:
                        raise CommandError("La revocación cambiaría permisos efectivos del rol Django o service_role; transacción revertida.")

    def save_plan(self, filename, plan):
        output = Path(filename).expanduser().absolute()
        resolved = output.resolve()
        project = Path(settings.BASE_DIR).parent.resolve()
        # Sandbox roots can have an empty .git protection marker. An actual
        # repository has HEAD, or a worktree .git file pointing to gitdir.
        in_repository = any((parent / ".git" / "HEAD").exists() or (parent / ".git").is_file() for parent in resolved.parents)
        if resolved.is_relative_to(project) or ".git" in resolved.parts or in_repository:
            raise CommandError("Guarda el plan fuera del proyecto y de cualquier repositorio Git.")
        if not output.parent.is_dir():
            raise CommandError("El directorio padre del plan debe existir.")
        try:
            descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump(plan, stream, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
        except OSError:
            raise CommandError("No se pudo crear un plan privado nuevo; ningún permiso modificado.") from None
