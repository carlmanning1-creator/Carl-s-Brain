import { getServerSession } from "next-auth";
import { authOptions } from "@/lib/auth";
import { redirect } from "next/navigation";
import Sidebar from "@/components/Sidebar";
import CalendarView from "@/components/calendar/CalendarView";

export default async function CalendarPage() {
  const session = await getServerSession(authOptions);
  if (!session) redirect("/login");

  return (
    <div className="flex min-h-screen bg-[#1C1B1F]">
      <Sidebar />
      <main className="ml-60 flex-1 p-8 overflow-y-auto min-h-screen">
        <CalendarView />
      </main>
    </div>
  );
}
