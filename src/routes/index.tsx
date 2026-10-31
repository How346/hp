import { createFileRoute } from "@tanstack/react-router";
import { PrintDesk } from "@/components/print-desk";

export const Route = createFileRoute("/")({ component: PrintDesk });
