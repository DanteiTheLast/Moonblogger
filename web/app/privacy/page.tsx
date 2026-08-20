import type { Metadata } from "next";
import styles from "./page.module.css";

export const metadata: Metadata = { title: "Privacidad | MoonBlogger" };

export default function PrivacyPage() {
  const contact = process.env.PRIVACY_CONTACT_EMAIL;
  return (
    <article className={styles.privacy}>
      <header className={styles.header}>
        <p className={styles.eyebrow}>MoonBlogger</p>
        <h1>Privacidad</h1>
      </header>
      <section aria-labelledby="datos-title">
        <h2 id="datos-title">Datos que recibimos</h2>
        <p>Para medir las visitas, MoonBlogger recibe la IP completa, el agente de usuario, la ruta visitada y la fecha. Conservamos estos datos durante 30 días.</p>
      </section>
      <section aria-labelledby="uso-title">
        <h2 id="uso-title">Uso de los datos</h2>
        <p>El envío se realiza a través de Vercel y se remite a nuestro proveedor de API. No usamos cookies ni elaboramos perfiles.</p>
      </section>
      <section aria-labelledby="contacto-title">
        <h2 id="contacto-title">Contacto</h2>
        <p>Para consultas sobre privacidad, {contact ? <a href={`mailto:${contact}`}>{contact}</a> : "consulta al administrador del sitio."}</p>
      </section>
    </article>
  );
}
