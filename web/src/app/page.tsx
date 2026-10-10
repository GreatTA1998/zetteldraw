import { Overview } from "@/components/overview";
import { googleClientId } from "@/lib/server-config";

export default function Home() {
  return <Overview googleClientId={googleClientId()} />;
}
