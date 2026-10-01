/** Answer validation, mirroring the phone's `FieldValidator` (same rule syntax and messages). */

const EMAIL = /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/;

export function validateValue(value: string, fieldType: string | null | undefined, rules: string[]): string | null {
  const v = value.trim();
  const explicit = rules.map((r) => r.trim()).filter(Boolean);
  if (!v) return explicit.includes("required") ? "This field is required" : null;
  const implicit = fieldType === "EMAIL" ? ["email"] : fieldType === "PHONE" ? ["phone"] : fieldType === "PINCODE" ? ["pincode"] : [];
  for (const rule of [...implicit, ...explicit]) {
    const name = rule.split(":")[0]!.trim().toLowerCase();
    const arg = rule.includes(":") ? rule.slice(rule.indexOf(":") + 1).trim() : "";
    const n = /^\d+$/.test(arg) ? Number(arg) : null;
    let error: string | null = null;
    switch (name) {
      case "email":
        if (!EMAIL.test(v.toLowerCase())) error = "That doesn't look like an email address";
        break;
      case "phone": {
        const digits = v.replace(/\D/g, "").length;
        if (digits < 10 || digits > 13) error = "Please say a 10 digit mobile number";
        break;
      }
      case "pincode":
        if (!/^\d{6}$/.test(v)) error = "PIN code must be 6 digits";
        break;
      case "digits":
        if (n !== null && !(v.length === n && /^\d+$/.test(v))) error = `Please say exactly ${n} digits`;
        break;
      case "min":
        if (n !== null && v.length < n) error = `Please say at least ${n} characters`;
        break;
      case "max":
        if (n !== null && v.length > n) error = `That is longer than ${n} characters`;
        break;
      case "regex":
        try {
          if (!new RegExp(`^(?:${arg})$`).test(v)) error = "That value is not in the expected format";
        } catch {
          error = null;
        }
        break;
      case "oneof": {
        const options = arg.split("|").map((o) => o.trim().toLowerCase());
        if (!options.includes(v.toLowerCase())) error = `Please choose one of: ${options.join(", ")}`;
        break;
      }
    }
    if (error) return error;
  }
  return null;
}
