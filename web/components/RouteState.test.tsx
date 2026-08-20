import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import PixelScene from "./PixelScene";
import RouteState from "./RouteState";

describe("route state components", () => {
  it("keeps its scene out of the accessibility tree", () => {
    const markup = renderToStaticMarkup(<PixelScene variant="hero" />);

    expect(markup).toContain('aria-hidden="true"');
  });

  it("uses loading semantics without a static status announcement", () => {
    const markup = renderToStaticMarkup(
      <RouteState title="Cargando" description="Espera un momento." variant="loading" />,
    );

    expect(markup).toContain('aria-busy="true"');
    expect(markup).not.toContain('role="status"');
  });

  it("preserves link and button action semantics and only announces opted-in errors", () => {
    const linkMarkup = renderToStaticMarkup(
      <RouteState title="Vacío" description="Sin resultados." variant="empty" action={{ label: "Volver", href: "/" }} />,
    );
    const buttonMarkup = renderToStaticMarkup(
      <RouteState title="Error" description="Inténtalo otra vez." variant="error" announceError action={{ label: "Reintentar", onClick: () => undefined }} />,
    );

    expect(linkMarkup).toContain('href="/"');
    expect(buttonMarkup).toContain("<button");
    expect(buttonMarkup).toContain('role="alert"');
  });
});
