import { z } from "zod";

export const elementKinds = ["TEXT_FIELD", "BUTTON", "CHECKBOX", "SWITCH", "RADIO", "DROPDOWN", "LINK"] as const;
export const fieldTypes = [
  "TEXT", "NAME", "EMAIL", "PHONE", "NUMBER", "PASSWORD", "OTP", "PIN", "DATE", "ADDRESS", "PINCODE", "MULTILINE", "SEARCH", "URL", "AMOUNT",
] as const;
export const sensitiveFieldTypes = new Set<string>(["PASSWORD", "OTP", "PIN"]);
export const stepActions = ["FILL", "CLICK", "TOGGLE", "READ", "SET_VARIABLE", "REPEAT", "NEXT_SCREEN", "OPEN_APP"] as const;
export type StepActionName = (typeof stepActions)[number];
/** Steps that operate an element on screen; the rest are flow logic. */
export const elementActions = new Set<string>(["FILL", "CLICK", "TOGGLE", "READ"]);
/** Steps that start a new screen of the flow. */
export const boundaryActions = new Set<string>(["NEXT_SCREEN", "OPEN_APP"]);
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

export const repeatSpecSchema = z.object({
  stepIds: z.array(z.string()).default([]),
  addMoreElementId: z.string().nullish(),
  addMoreLabel: z.string().nullish(),
  maxIterations: z.number().default(10),
  countExpression: z.string().nullish(),
  itemLabel: z.string().nullish(),
});
export type RepeatSpec = z.infer<typeof repeatSpecSchema>;

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
  condition: z.string().nullish(),
  elseValue: z.string().nullish(),
  variable: z.string().nullish(),
  valueExpression: z.string().nullish(),
  repeat: repeatSpecSchema.nullish(),
  appPackage: z.string().nullish(),
  waitSeconds: z.number().nullish(),
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

export const stepOutcomes = ["FILLED", "DEFAULT_FILLED", "KEPT", "SKIPPED", "MANUAL", "CLICKED", "TOGGLED", "FAILED"] as const;

export const runSchema = z.object({
  sessionId: z.string(),
  appPackage: z.string(),
  startedAtMillis: z.number(),
  endedAtMillis: z.number(),
  status: z.enum(["COMPLETED", "STOPPED", "FAILED"]),
  language: z.enum(["ENGLISH", "HINDI", "HINGLISH"]),
  filledCount: z.number().default(0),
  stepCount: z.number().default(0),
  screens: z.array(
    z.object({
      appPackage: z.string(),
      activityName: z.string().nullish(),
      screenTitle: z.string().nullish(),
      screenSignature: z.string(),
      flowId: z.string().nullish(),
      flowVersion: z.number().nullish(),
      steps: z.array(
        z.object({
          elementId: z.string(),
          label: z.string(),
          kind: z.enum(elementKinds),
          fieldType: z.enum(fieldTypes).nullish(),
          question: z.string().nullish(),
          outcome: z.enum(stepOutcomes),
          interpretedBy: z.string().nullish(),
        }),
      ),
    }),
  ),
});
export type Run = z.infer<typeof runSchema>;
export const runPageSchema = z.object({ items: z.array(runSchema), limit: z.number(), offset: z.number() });
export const runStatsSchema = z.object({
  totalRuns: z.number(),
  completedRuns: z.number(),
  fieldsFilled: z.number(),
  topApps: z.array(z.object({ appPackage: z.string(), runs: z.number() })),
});
export type RunStats = z.infer<typeof runStatsSchema>;
