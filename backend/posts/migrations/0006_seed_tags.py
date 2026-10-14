from django.db import migrations

CATALOG = [
    ("diario", "Diario"), ("ideas", "Ideas"), ("relatos", "Relatos"),
    ("comida", "Comida"), ("estudio", "Estudio"), ("viajes", "Viajes"),
    ("fotografia", "Fotografía"), ("mascotas", "Mascotas"), ("dan", "Dan"),
]


def seed(apps, schema_editor):
    Tag = apps.get_model("posts", "Tag")
    for order, (slug, name) in enumerate(CATALOG):
        Tag.objects.using(schema_editor.connection.alias).get_or_create(
            slug=slug, defaults={"name": name, "sort_order": order, "is_active": True}
        )


class Migration(migrations.Migration):
    dependencies = [("posts", "0005_tag_post_tags")]
    # Reversing the catalog seed must not delete associations created by users.
    operations = [migrations.RunPython(seed, migrations.RunPython.noop)]
