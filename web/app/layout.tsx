import type { Metadata } from "next";
import { Baloo_2, Nunito } from "next/font/google";
import Header from "@/components/Header";
import Footer from "@/components/Footer";
import VisitTracker from "@/components/VisitTracker";
import styles from "./layout.module.css";
import "./globals.css";

const nunito = Nunito({
  variable: "--font-nunito",
  subsets: ["latin"],
});

const baloo = Baloo_2({
  variable: "--font-baloo",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "MoonBlogger",
  description: "El blog público de Moon: sus publicaciones, en orden.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="es" className={`${nunito.variable} ${baloo.variable}`}>
      <body>
        <a href="#main-content" className={styles.skipLink}>
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
