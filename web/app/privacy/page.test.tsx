import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import PrivacyPage from "./page";

describe("PrivacyPage", () => {
  it("keeps the privacy notice in a structured reading layout", () => {
    const markup = renderToStaticMarkup(<PrivacyPage />);

    expect(markup).toContain("<article");
    expect(markup).toContain("<h1>Privacidad</h1>");
    expect(markup).toContain("Datos que recibimos");
    expect(markup).toContain("Uso de los datos");
    expect(markup).toContain("Contacto");
    expect(markup).toContain("Conservamos estos datos durante 30 días.");
  });
});
