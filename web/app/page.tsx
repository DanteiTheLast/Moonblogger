import type { Metadata } from "next";
import PostFeed from "@/components/PostFeed";

export const metadata: Metadata = {
  title: "MoonBlogger",
  description: "El blog público de Moon: sus publicaciones, en orden.",
};

export const revalidate = 3600;

export default async function HomePage() {
  return PostFeed({ page: 1 });
}
