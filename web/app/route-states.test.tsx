import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import ErrorPage from "./error";
import Loading from "./loading";
import NotFound from "./not-found";

describe("app route states", () => {
  it("renders loading as a busy state without a static status announcement", () => {
    const markup = renderToStaticMarkup(<Loading />);

    expect(markup).toContain("Cargando publicaciones");
    expect(markup).toContain('aria-busy="true"');
    expect(markup).not.toContain('role="status"');
  });

  it("renders the error boundary with an announced semantic retry button", () => {
    const markup = renderToStaticMarkup(<ErrorPage error={new Error("test")} reset={() => undefined} />);

    expect(markup).toContain('role="alert"');
    expect(markup).toContain("<button");
    expect(markup).toContain("Reintentar");
  });

  it("renders the not-found recovery as a link to the home route", () => {
    const markup = renderToStaticMarkup(<NotFound />);

    expect(markup).toContain('href="/"');
    expect(markup).toContain("Volver al listado");
  });
});
