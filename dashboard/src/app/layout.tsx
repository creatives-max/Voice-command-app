import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "VoiceControl Dashboard",
  description: "Edit and manage saved voice flows for VoiceControl.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}
