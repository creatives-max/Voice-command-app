import { z } from "zod";

export const elementKinds = ["TEXT_FIELD", "BUTTON", "CHECKBOX", "SWITCH", "RADIO", "DROPDOWN", "LINK"] as const;
export const fieldTypes = [
  "TEXT", "NAME", "EMAIL", "PHONE", "NUMBER", "PASSWORD", "OTP", "PIN", "DATE", "ADDRESS", "PINCODE", "MULTILINE", "SEARCH", "URL", "AMOUNT",
] as const;
export const sensitiveFieldTypes = new Set<string>(["PASSWORD", "OTP", "PIN"]);
export const stepActions = ["FILL", "CLICK", "TOGGLE"] as const;
export const profileKeys = [
  "FULL_NAME", "FIRST_NAME", "LAST_NAME", "EMAIL", "PHONE", "ADDRESS_LINE", "CITY", "STATE", "PINCODE", "DATE_OF_BIRTH",
] as const;

export const userSchema = z.object({
  id: z.string(),
  email: z.string(),
  name: z.string().nullish(),
  createdAt: z.string().optional(),
});
export type User = z.infer<typeof userSchema>;

export const flowStepSchema = z.object({
  id: z.string(),
  order: z.number(),
  elementId: z.string(),
  label: z.string(),
  kind: z.enum(elementKinds),
  fieldType: z.enum(fieldTypes).nullish(),
  action: z.enum(stepActions).default("FILL"),
  question: z.string().nullish(),
  rules: z.array(z.string()).default([]),
  defaultValue: z.string().nullish(),
  skip: z.boolean().default(false),
  helpVideoUrl: z.string().nullish(),
  profileKey: z.enum(profileKeys).nullish(),
});
export type FlowStep = z.infer<typeof flowStepSchema>;

export const flowSchema = z.object({
  id: z.string(),
  version: z.number(),
  appPackage: z.string(),
  name: z.string(),
  screenSignature: z.string(),
  steps: z.array(flowStepSchema),
  updatedAtMillis: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Flow = z.infer<typeof flowSchema>;

export const flowSummarySchema = z.object({
  id: z.string(),
  appPackage: z.string(),
  name: z.string(),
  currentVersion: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type FlowSummary = z.infer<typeof flowSummarySchema>;

export const flowPageSchema = z.object({ items: z.array(flowSummarySchema), limit: z.number(), offset: z.number() });

export const flowVersionSchema = z.object({
  version: z.number(),
  steps: z.array(flowStepSchema),
  source: z.enum(["DEVICE", "DASHBOARD", "ROLLBACK"]),
  changeNote: z.string().nullish(),
  createdAt: z.string(),
});
export type FlowVersion = z.infer<typeof flowVersionSchema>;

export const appSummarySchema = z.object({ appPackage: z.string(), flowCount: z.number(), lastUpdated: z.string() });
export type AppSummary = z.infer<typeof appSummarySchema>;

export const profileSchema = z.object({
  fullName: z.string().nullish(),
  email: z.string().nullish(),
  phone: z.string().nullish(),
  addressLine: z.string().nullish(),
  city: z.string().nullish(),
  state: z.string().nullish(),
  pincode: z.string().nullish(),
  dateOfBirth: z.string().nullish(),
});
export type Profile = z.infer<typeof profileSchema>;

export const apiErrorSchema = z.object({ error: z.string(), message: z.string() });
