import Link from "next/link";
import type { Tag } from "@/lib/types";
import { filterHref } from "@/lib/tagFilters";
import styles from "./TagExplorer.module.css";
export default function Hashtags({ tags = [] }: { tags?: Tag[] }) {
  return tags.length ? <ul className={styles.hashtags} aria-label="Etiquetas de la publicación">{tags.map((tag) => <li key={tag.slug}><Link href={filterHref([tag.slug])} prefetch={false}>#{tag.name}</Link></li>)}</ul> : null;
}
