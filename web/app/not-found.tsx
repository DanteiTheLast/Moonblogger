import RouteState from "@/components/RouteState";

export default function NotFound() {
  return (
    <RouteState
      variant="notFound"
      title="No encontramos esa publicación"
      description="Puede que no exista o que todavía esté en borrador. Vuelve al listado para ver las publicaciones disponibles."
      action={{ label: "Volver al listado", href: "/" }}
    />
  );
}
