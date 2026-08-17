import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { PostMedia } from "@/lib/types";
import MediaCarousel from "./MediaCarousel";
import {
  getMediaAspectRatio,
  getSlideIndex,
  normalizeCarouselTransition,
  orderMedia,
} from "./mediaCarousel.logic";

const IMAGE: PostMedia = {
  id: "image-1",
  kind: "image",
  position: 0,
  is_cover: true,
  mime_type: "image/webp",
  width: 1200,
  height: 800,
  duration_seconds: null,
  alt_text: "Una luna rosa",
  caption: "Luna llena",
  url: "https://project.supabase.co/storage/v1/object/public/media/moon.webp",
  poster_url: null,
};

const VIDEO: PostMedia = {
  ...IMAGE,
  id: "video-2",
  kind: "video",
  position: 1,
  mime_type: "video/mp4",
  width: null,
  height: null,
  duration_seconds: 12,
  alt_text: "Vídeo de la noche",
  caption: "",
  url: "https://project.supabase.co/storage/v1/object/public/media/night.mp4",
  poster_url: "https://project.supabase.co/storage/v1/object/public/media/night.jpg",
};

describe("MediaCarousel logic", () => {
  it("normaliza transiciones y rota los índices", () => {
    expect(normalizeCarouselTransition("bubble")).toBe("bubble");
    expect(normalizeCarouselTransition("unexpected")).toBe("none");
    expect(getSlideIndex(0, 2, -1)).toBe(1);
    expect(getSlideIndex(1, 2, 1)).toBe(0);
  });

  it("ordena por posición y usa una relación de aspecto de reserva", () => {
    expect(orderMedia([VIDEO, IMAGE]).map((item) => item.id)).toEqual([
      "image-1",
      "video-2",
    ]);
    expect(getMediaAspectRatio(IMAGE)).toBe("1200 / 800");
    expect(getMediaAspectRatio(VIDEO)).toBe("16 / 9");
  });
});

describe("MediaCarousel UI", () => {
  it("renderiza la imagen inicial, controles accesibles y el texto alternativo", () => {
    const markup = renderToStaticMarkup(
      <MediaCarousel media={[VIDEO, IMAGE]} transition="fade" />,
    );

    expect(markup).toContain('aria-roledescription="carrusel"');
    expect(markup).toContain('alt="Una luna rosa"');
    expect(markup).toContain('aria-label="Elemento anterior"');
    expect(markup).toContain("Luna llena");
  });

  it("renderiza vídeo con controles, póster y precarga de metadatos", () => {
    const markup = renderToStaticMarkup(
      <MediaCarousel media={[VIDEO]} transition="none" />,
    );

    expect(markup).toContain("<video");
    expect(markup).toContain("controls");
    expect(markup).toContain('playsInline=""');
    expect(markup).toContain('preload="metadata"');
    expect(markup).toContain('poster="https://project.supabase.co/storage/v1/object/public/media/night.jpg"');
    expect(markup).toContain('type="video/mp4"');
  });
});
