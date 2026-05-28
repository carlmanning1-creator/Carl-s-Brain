import { getServerSession } from "next-auth";
import { authOptions } from "@/lib/auth";
import { redirect } from "next/navigation";
import Sidebar from "@/components/Sidebar";
import DashboardContent from "@/components/dashboard/DashboardContent";

export default async function DashboardPage() {
  const session = await getServerSession(authOptions);
  if (!session) redirect("/login");

  return (
    <div className="flex min-h-screen bg-[#1C1B1F]">
      <Sidebar />
      <main className="ml-60 flex-1 p-8 overflow-y-auto min-h-screen">
        <DashboardContent />
      </main>
    </div>
  );
}
