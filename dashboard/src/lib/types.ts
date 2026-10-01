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
  sourcePublishedId: z.string().nullish(),
  sourceVersion: z.number().nullish(),
  orgId: z.string().nullish(),
});
export type Flow = z.infer<typeof flowSchema>;

export const flowSummarySchema = z.object({
  id: z.string(),
  appPackage: z.string(),
  name: z.string(),
  currentVersion: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  orgId: z.string().nullish(),
});
export type FlowSummary = z.infer<typeof flowSummarySchema>;

export const flowPageSchema = z.object({ items: z.array(flowSummarySchema), limit: z.number(), offset: z.number() });

export const flowVersionSchema = z.object({
  version: z.number(),
  steps: z.array(flowStepSchema),
  source: z.enum(["DEVICE", "DASHBOARD", "ROLLBACK", "IMPORT", "RECORDED", "CAREGIVER"]),
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

export const languages = ["ENGLISH", "HINDI", "HINGLISH", "MARATHI", "TAMIL", "TELUGU", "BENGALI", "GUJARATI"] as const;
export const LANGUAGE_NAMES: Record<(typeof languages)[number], string> = {
  ENGLISH: "English",
  HINDI: "हिन्दी",
  HINGLISH: "Hinglish",
  MARATHI: "मराठी",
  TAMIL: "தமிழ்",
  TELUGU: "తెలుగు",
  BENGALI: "বাংলা",
  GUJARATI: "ગુજરાતી",
};

export const stepOutcomes = ["FILLED", "DEFAULT_FILLED", "KEPT", "SKIPPED", "MANUAL", "CLICKED", "TOGGLED", "FAILED"] as const;

export const runSchema = z.object({
  sessionId: z.string(),
  appPackage: z.string(),
  startedAtMillis: z.number(),
  endedAtMillis: z.number(),
  status: z.enum(["COMPLETED", "STOPPED", "FAILED"]),
  language: z.enum(languages),
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

export const deviceSchema = z.object({
  id: z.string(),
  name: z.string(),
  platform: z.string(),
  appVersion: z.string().nullish(),
  remoteRuns: z.boolean(),
  lastSeenAt: z.string(),
  online: z.boolean(),
  createdAt: z.string(),
});
export type Device = z.infer<typeof deviceSchema>;

export const triggerTypes = ["APP_OPEN", "SCHEDULE", "VOICE"] as const;
export const triggerSchema = z.object({
  id: z.string(),
  flowId: z.string(),
  type: z.enum(triggerTypes),
  enabled: z.boolean(),
  cron: z.string().nullish(),
  timezone: z.string().nullish(),
  deviceId: z.string().nullish(),
  nextRunAt: z.string().nullish(),
  lastRunAt: z.string().nullish(),
  upcoming: z.array(z.string()).default([]),
  /** VOICE: what the user says to run the flow (a voice macro). */
  phrase: z.string().nullish(),
});
export type Trigger = z.infer<typeof triggerSchema>;

export const runRequestStatuses = [
  "PENDING", "DELIVERED", "RUNNING", "COMPLETED", "FAILED", "STOPPED", "CANCEL_REQUESTED", "CANCELLED", "EXPIRED",
] as const;
export const finalRunStatuses = new Set<string>(["COMPLETED", "FAILED", "STOPPED", "CANCELLED", "EXPIRED"]);
export const runRequestSchema = z.object({
  id: z.string(),
  flowId: z.string().nullish(),
  flowName: z.string(),
  appPackage: z.string(),
  deviceId: z.string().nullish(),
  triggerId: z.string().nullish(),
  source: z.enum(["MANUAL", "SCHEDULE", "APP_OPEN", "VOICE"]),
  status: z.enum(runRequestStatuses),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type RunRequest = z.infer<typeof runRequestSchema>;
export const runEventSchema = z.object({ id: z.number(), at: z.string(), kind: z.string(), message: z.string() });
export type RunEvent = z.infer<typeof runEventSchema>;
export const runEventsSchema = z.object({ request: runRequestSchema, events: z.array(runEventSchema) });

export const listingSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string(),
  appPackage: z.string(),
  category: z.string(),
  tags: z.array(z.string()),
  isTemplate: z.boolean(),
  ownerName: z.string().nullish(),
  mine: z.boolean(),
  latestVersion: z.number(),
  installCount: z.number(),
  ratingAverage: z.number().nullish(),
  ratingCount: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  /** Hidden from search after reports (its owner still sees it). */
  hidden: z.boolean().default(false),
});
export type Listing = z.infer<typeof listingSchema>;
export const listingPageSchema = z.object({ items: z.array(listingSchema), limit: z.number(), offset: z.number() });
export const ratingSchema = z.object({ stars: z.number(), review: z.string().nullish(), userName: z.string().nullish(), updatedAt: z.string() });
export type ListingRating = z.infer<typeof ratingSchema>;
export const listingDetailSchema = z.object({
  listing: listingSchema,
  steps: z.array(flowStepSchema),
  keywords: z.record(z.array(z.string())).default({}),
  versions: z.array(z.object({ version: z.number(), changelog: z.string().nullish(), stepCount: z.number(), createdAt: z.string() })),
  myRating: ratingSchema.nullish(),
  reviews: z.array(ratingSchema),
  importedFlowId: z.string().nullish(),
  importedVersion: z.number().nullish(),
  updateAvailable: z.boolean(),
  /** What the viewer reported about this listing (BROKEN, UNSAFE, SPAM, OTHER). */
  myReport: z.string().nullish(),
});
export type ListingDetail = z.infer<typeof listingDetailSchema>;
export const templateSchema = z.object({ listing: listingSchema, steps: z.array(flowStepSchema), keywords: z.record(z.array(z.string())) });
export type Template = z.infer<typeof templateSchema>;

export const roles = ["ADMIN", "EDITOR", "VIEWER"] as const;
export const ROLE_LABELS: Record<(typeof roles)[number], string> = { ADMIN: "Admin", EDITOR: "Editor", VIEWER: "Viewer" };
export const orgSchema = z.object({ id: z.string(), name: z.string(), role: z.enum(roles), memberCount: z.number(), createdAt: z.string() });
export type Org = z.infer<typeof orgSchema>;
export const memberSchema = z.object({ userId: z.string(), email: z.string(), name: z.string().nullish(), role: z.enum(roles), joinedAt: z.string() });
export type Member = z.infer<typeof memberSchema>;
export const invitationSchema = z.object({
  id: z.string(),
  email: z.string(),
  role: z.enum(roles),
  createdAt: z.string(),
  expiresAt: z.string(),
  token: z.string().nullish(),
});
export type Invitation = z.infer<typeof invitationSchema>;
export const invitationPreviewSchema = z.object({ orgName: z.string(), email: z.string(), role: z.enum(roles), expiresAt: z.string() });

export const apiScopes = ["flows:read", "flows:write", "runs:write"] as const;
export const SCOPE_LABELS: Record<(typeof apiScopes)[number], string> = {
  "flows:read": "Read flows",
  "flows:write": "Edit and delete flows",
  "runs:write": "Start runs on your phone",
};
export const apiKeySchema = z.object({
  id: z.string(),
  name: z.string(),
  prefix: z.string(),
  scopes: z.array(z.string()),
  rateLimitPerMinute: z.number(),
  createdAt: z.string(),
  lastUsedAt: z.string().nullish(),
  revokedAt: z.string().nullish(),
  secret: z.string().nullish(),
  expiresAt: z.string().nullish(),
  expired: z.boolean().default(false),
  rotatedAt: z.string().nullish(),
});
export type ApiKey = z.infer<typeof apiKeySchema>;

export const orgUsageSchema = z.object({
  members: z.number(),
  pendingInvitations: z.number(),
  flows: z.number(),
  activeApiKeys: z.number(),
  webhooks: z.number(),
  runs30d: z.number(),
  maxMembers: z.number(),
  maxApiKeys: z.number(),
  maxWebhooks: z.number(),
});
export type OrgUsage = z.infer<typeof orgUsageSchema>;

export const webhookEvents = ["flow.version_saved", "flow.deleted", "run.finished"] as const;
export const WEBHOOK_EVENT_LABELS: Record<(typeof webhookEvents)[number], string> = {
  "flow.version_saved": "Flow saved (new version)",
  "flow.deleted": "Flow deleted",
  "run.finished": "Remote run finished",
};
export const webhookSchema = z.object({
  id: z.string(),
  url: z.string(),
  events: z.array(z.string()),
  active: z.boolean(),
  createdAt: z.string(),
  secret: z.string().nullish(),
});
export type Webhook = z.infer<typeof webhookSchema>;
export const deliverySchema = z.object({
  id: z.string(),
  eventId: z.string(),
  eventType: z.string(),
  status: z.enum(["PENDING", "SUCCEEDED", "FAILED"]),
  attempts: z.number(),
  nextAttemptAt: z.string(),
  lastStatusCode: z.number().nullish(),
  lastError: z.string().nullish(),
  createdAt: z.string(),
  deliveredAt: z.string().nullish(),
  payload: z.string(),
});
export type Delivery = z.infer<typeof deliverySchema>;
export const auditEntrySchema = z.object({
  id: z.number(),
  action: z.string(),
  targetType: z.string(),
  targetId: z.string().nullish(),
  actorUserId: z.string().nullish(),
  actorEmail: z.string().nullish(),
  actorApiKeyId: z.string().nullish(),
  details: z.record(z.string()),
  at: z.string(),
});
export type AuditEntry = z.infer<typeof auditEntrySchema>;

export const usageSchema = z.object({
  runs: z.number(),
  completed: z.number(),
  stopped: z.number(),
  failed: z.number(),
  users: z.number(),
  successRate: z.number().nullish(),
  avgDurationMillis: z.number().nullish(),
  lastRunAt: z.string().nullish(),
});
export type Usage = z.infer<typeof usageSchema>;
export const dailySchema = z.object({ date: z.string(), completed: z.number(), stopped: z.number(), failed: z.number() });
export type Daily = z.infer<typeof dailySchema>;
export const stepStatsSchema = z.object({ elementId: z.string(), label: z.string(), total: z.number(), outcomes: z.record(z.number()) });
export type StepStats = z.infer<typeof stepStatsSchema>;
export const flowAnalyticsSchema = z.object({
  usage: usageSchema,
  daily: z.array(dailySchema),
  steps: z.array(stepStatsSchema),
  interpretedBy: z.record(z.number()),
  remoteRuns: z.record(z.number()),
  previous: usageSchema.nullish(),
});
export type FlowAnalytics = z.infer<typeof flowAnalyticsSchema>;
export const analyticsOverviewSchema = z.object({
  flows: z.array(z.object({ flowId: z.string(), name: z.string(), appPackage: z.string(), usage: usageSchema })),
  daily: z.array(dailySchema),
  totals: usageSchema,
  previous: usageSchema.nullish(),
});
export type AnalyticsOverview = z.infer<typeof analyticsOverviewSchema>;

export const commentSchema = z.object({
  id: z.string(),
  flowId: z.string(),
  userId: z.string().nullish(),
  authorName: z.string().nullish(),
  authorEmail: z.string().nullish(),
  stepId: z.string().nullish(),
  body: z.string(),
  createdAt: z.string(),
  editedAt: z.string().nullish(),
  resolvedAt: z.string().nullish(),
});
export type Comment = z.infer<typeof commentSchema>;
export const presenceSchema = z.object({
  others: z.array(z.object({ userId: z.string(), name: z.string(), editing: z.boolean(), seenAt: z.string() })),
  currentVersion: z.number(),
});
export type PresenceInfo = z.infer<typeof presenceSchema>;
export const layoutSchema = z.object({ positions: z.record(z.object({ x: z.number(), y: z.number() })) });
export type Layout = z.infer<typeof layoutSchema>;

export const crashGroupSchema = z.object({
  fingerprint: z.string(),
  exception: z.string(),
  message: z.string().nullish(),
  topFrame: z.string().nullish(),
  count: z.number(),
  firstSeen: z.string(),
  lastSeen: z.string(),
  appVersions: z.array(z.string()),
  latestStacktrace: z.string(),
});
export type CrashGroup = z.infer<typeof crashGroupSchema>;

export const carePermissions = ["edit_flows", "run_flows", "view_history"] as const;
export type CarePermission = (typeof carePermissions)[number];
export const careLinkSchema = z.object({
  id: z.string(),
  /** "receiver": the caller is being helped; "caregiver": the caller helps the other person. */
  role: z.enum(["receiver", "caregiver"]),
  status: z.enum(["PENDING", "ACTIVE", "REVOKED"]),
  permissions: z.array(z.string()),
  otherEmail: z.string().nullish(),
  otherName: z.string().nullish(),
  createdAt: z.string(),
  acceptedAt: z.string().nullish(),
  expiresAt: z.string().nullish(),
});
export type CareLink = z.infer<typeof careLinkSchema>;
export const careInviteSchema = z.object({ link: careLinkSchema, code: z.string(), expiresAt: z.string() });
export type CareInvite = z.infer<typeof careInviteSchema>;
export const careEventSchema = z.object({
  id: z.number(),
  action: z.string(),
  actorEmail: z.string().nullish(),
  details: z.record(z.string()).default({}),
  at: z.string(),
});
export type CareEvent = z.infer<typeof careEventSchema>;
