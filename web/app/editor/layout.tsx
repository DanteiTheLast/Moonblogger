import type { Metadata } from "next";
import EditorSession from "@/components/editor/EditorSession";
export const dynamic = "force-dynamic";
export const revalidate = 0;
export const metadata: Metadata = {title: "Escribir · MoonBlogger", description: "Espacio privado para crear y editar publicaciones.", robots: {index:false,follow:false,nocache:true}, referrer:"same-origin"};
export default function EditorLayout({children}:{children:React.ReactNode}) { return <EditorSession>{children}</EditorSession>; }
