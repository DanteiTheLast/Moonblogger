import RouteState from "@/components/RouteState";

export default function Loading() {
  return (
    <RouteState
      variant="loading"
      title="Cargando publicaciones"
      description="Estamos preparando el contenido para ti."
    />
  );
}
