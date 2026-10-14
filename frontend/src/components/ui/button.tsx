import type { ButtonHTMLAttributes } from "react";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "ghost" };

/** Small shadcn-style primitive: accessible native button with project variants. */
export function Button({ variant = "primary", className = "", ...props }: ButtonProps) {
  return <button className={`button ${variant} ${className}`.trim()} {...props} />;
}
