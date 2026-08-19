import type { Metadata } from "next";

export const metadata: Metadata = { title: "Privacidad | MoonBlogger" };

export default function PrivacyPage() {
  return <article><h1>Privacidad</h1><p>Para medir las visitas, MoonBlogger recibe la IP completa, el agente de usuario, la ruta visitada y la fecha. Conservamos estos datos durante 30 días.</p><p>El envío se realiza a través de Vercel y se remite a nuestro proveedor de API. No usamos cookies ni elaboramos perfiles.</p><p>Para consultas sobre privacidad, escribe a <a href="mailto:privacidad@moonblogger.example">privacidad@moonblogger.example</a>.</p></article>;
}
