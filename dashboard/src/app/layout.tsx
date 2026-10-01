import type { Metadata } from "next";
import "@xyflow/react/dist/style.css";
import "./globals.css";
import { THEME_BOOT_SCRIPT } from "@/lib/theme-script";

export const metadata: Metadata = {
  title: "VoiceControl Dashboard",
  description: "Edit and manage saved voice flows for VoiceControl.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
