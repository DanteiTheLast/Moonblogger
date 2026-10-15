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
        <p>El envío se realiza a través de Vercel y se remite a nuestro proveedor de API. No usamos cookies de seguimiento ni elaboramos perfiles.</p>
      </section>
      <section aria-labelledby="editor-title">
        <h2 id="editor-title">Editor privado</h2>
        <p>Al iniciar sesión en el editor usamos dos cookies esenciales, protegidas frente al acceso de JavaScript, para mantener la sesión. No se usan para seguimiento y se eliminan al cerrar sesión o al caducar.</p>
        <p>El editor puede guardar en este navegador una copia del título, texto y etiquetas, junto con el estado de publicación y los datos necesarios para recuperar la edición: identificadores, orden y portada de los archivos, textos alternativos, pies de foto, retiradas pendientes y progreso del guardado. Esta copia se limita a la cuenta que inició sesión; no incluye contraseñas, tokens, direcciones temporales de los archivos ni los archivos adjuntos. En un equipo compartido, cierra sesión al terminar y elimina la copia local cuando ya no la necesites.</p>
      </section>
      <section aria-labelledby="contacto-title">
        <h2 id="contacto-title">Contacto</h2>
        <p>Para consultas sobre privacidad, {contact ? <a href={`mailto:${contact}`}>{contact}</a> : "consulta al administrador del sitio."}</p>
      </section>
    </article>
  );
}
