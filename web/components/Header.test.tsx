import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import Footer from "./Footer";
import Header from "./Header";

describe("site chrome", () => {
  it("keeps the brand link, decorative trail marker, and theme control in the header", () => {
    const markup = renderToStaticMarkup(<Header />);

    expect(markup).toContain('href="/"');
    expect(markup).toContain("MoonBlogger");
    expect(markup).toContain('aria-hidden="true"');
    expect(markup).not.toContain("aria-pressed");
    expect(markup).toContain('aria-label="Cambiar tema"');
  });

  it("keeps the privacy link and legal text in the footer", () => {
    const markup = renderToStaticMarkup(<Footer />);

    expect(markup).toContain("MoonBlogger: el blog de Moon.");
    expect(markup).toContain('href="/privacy"');
    expect(markup).toContain('aria-hidden="true"');
  });
});
