import type { Metadata } from "next";
import Script from "next/script";
import { Nunito, Pixelify_Sans } from "next/font/google";
import Header from "@/components/Header";
import Footer from "@/components/Footer";
import VisitTracker from "@/components/VisitTracker";
import styles from "./layout.module.css";
import "./globals.css";

const nunito = Nunito({
  variable: "--font-nunito",
  subsets: ["latin"],
});

const pixelifySans = Pixelify_Sans({
  variable: "--font-pixelify-sans",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "MoonBlogger",
  description: "El blog público de Moon: sus publicaciones, en orden.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="es"
      className={`${nunito.variable} ${pixelifySans.variable}`}
      suppressHydrationWarning
    >
      <head>
        <Script id="theme-initializer" strategy="beforeInteractive">
          {`try {
            var storedTheme = localStorage.getItem("moonblogger-theme");
            var theme = storedTheme === "light" || storedTheme === "dark"
              ? storedTheme
              : (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
            document.documentElement.dataset.theme = theme;
          } catch (_) {}`}
        </Script>
      </head>
      <body>
        <a href="#main-content" className="skipLink">
          Saltar al contenido
        </a>
        <Header />
        <VisitTracker />
        <main id="main-content" className={styles.main}>
          {children}
        </main>
        <Footer />
      </body>
    </html>
  );
}
