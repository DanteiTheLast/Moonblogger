import {notFound} from "next/navigation";
import PostEditor from "@/components/editor/PostEditor";
export default async function EditPostPage({params}:{params:Promise<{id:string}>}) {
  const {id} = await params;
  if (!/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))) notFound();
  return <PostEditor postId={Number(id)} key={id}/>;
}
