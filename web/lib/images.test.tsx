import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { getImageProps } from "next/image";
import { hasRemoteMatch } from "next/dist/shared/lib/match-remote-pattern";
import nextConfig from "../next.config";
import PostThumbnail from "../components/PostThumbnail";
import { GALLERY_SIZES, IMAGE_QUALITY } from "./images";
const url = "https://zubconvayinjmnbqqxla.supabase.co/storage/v1/object/public/moonblogger-public/publications/id/asset";
describe("responsive public images", () => {
  it("limits the optimizer to our public bucket without redirects or query strings", () => {
    const patterns = nextConfig.images!.remotePatterns!;
    expect(hasRemoteMatch([], patterns, new URL(url))).toBe(true);
    expect(hasRemoteMatch([], patterns, new URL(url.replace("publications/id/asset", "posts/12/id/asset")))).toBe(true);
    for (const forbidden of [url.replace("zubconvayinjmnbqqxla", "another-project"),
      url.replace("object/public/moonblogger-public", "object/sign/moonblogger-private"),
      url.replace("moonblogger-public", "another-bucket"), url.replace("https:", "http:"), url + "?download=1",
    ]) expect(hasRemoteMatch([], patterns, new URL(forbidden))).toBe(false);
    expect(nextConfig.images!.maximumRedirects).toBe(0);
  });
  it("generates adaptive optimizer URLs with bounded widths and quality", () => {
    const { props } = getImageProps({ src: url, alt: "Foto", fill: true, sizes: GALLERY_SIZES, quality: IMAGE_QUALITY });
    expect(props.src).toContain("/_next/image?url=" + encodeURIComponent(url));
    expect(props.srcSet).toContain("w=320&q=88 320w");
    expect(props.srcSet).toContain("w=1440&q=88 1440w");
    expect(props.srcSet).not.toContain("3840");
    expect(props.sizes).toBe(GALLERY_SIZES);
  });
  it("renders a lazy optimized thumbnail without downloading the original as fallback", () => {
    const markup = renderToStaticMarkup(<PostThumbnail url={url} alt="Mi foto" />);
    expect(markup).toContain('alt="Mi foto"');
    expect(markup).toContain('loading="lazy"');
    expect(markup).toContain('sizes="(min-width: 42rem) 320px, calc(100vw - 96px)"');
    expect(markup).toContain('src="/_next/image?url=');
    expect(markup).not.toContain('src="' + url + '"');
  });
});
