from django.db import migrations, models
import django.core.validators

class Migration(migrations.Migration):
    dependencies = [("posts", "0003_publicvisit")]
    operations = [migrations.CreateModel(
        name="PublicVisitEvent",
        fields=[
            ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
            ("event_id", models.UUIDField(editable=False, unique=True)),
            ("path", models.CharField(max_length=220)),
            ("ip_address", models.GenericIPAddressField(validators=[django.core.validators.validate_ipv46_address])),
            ("created_at", models.DateTimeField(auto_now_add=True)),
            ("visit", models.ForeignKey(on_delete=models.deletion.CASCADE, related_name="events", to="posts.publicvisit")),
        ],
        options={"indexes": [models.Index(fields=["visit", "created_at"], name="posts_publi_visit_i_7347e2_idx")]},
    )]
