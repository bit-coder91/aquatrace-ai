import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "AquaTrace — Water intelligence", description: "Understand water usage and investigate unusual flow." };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
