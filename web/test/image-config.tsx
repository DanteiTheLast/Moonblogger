// Next normally injects next.config at compile time; Vitest loads it explicitly.
import { vi } from "vitest";
vi.mock("next/image", async (importOriginal) => {
  const actual = await importOriginal<typeof import("next/image")>();
  const { imageConfigDefault } = await import("next/dist/shared/lib/image-config");
  const { ImageConfigContext } = await import("next/dist/shared/lib/image-config-context.shared-runtime");
  const { getImgProps } = await import("next/dist/shared/lib/get-img-props");
  const { default: defaultLoader } = await import("next/dist/shared/lib/image-loader");
  const { default: nextConfig } = await import("../next.config");
  const config = { ...imageConfigDefault, ...nextConfig.images };
  return {
    ...actual,
    default: (props: import("next/image").ImageProps) =>
      <ImageConfigContext.Provider value={config}><actual.default {...props} /></ImageConfigContext.Provider>,
    getImageProps: (props: import("next/image").ImageProps) =>
      ({ props: getImgProps(props, { defaultLoader, imgConf: config }).props }),
  };
});
