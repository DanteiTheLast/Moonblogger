/** Formatea una fecha ISO 8601 como texto largo en español (p. ej. "12 de agosto de 2026"). */
export function formatDate(isoDate: string): string {
  const date = new Date(isoDate);
  if (Number.isNaN(date.getTime())) {
    return isoDate;
  }
  return new Intl.DateTimeFormat("es", {
    year: "numeric",
    month: "long",
    day: "numeric",
  }).format(date);
}
