import { Link } from "@tanstack/react-router";

const SECTIONS: [string, string][] = [
  [
    "What VoiceControl reads",
    "When the accessibility service is on, VoiceControl reads the labels, types and current values of input fields and buttons on the app in front of you, only to ask you questions and fill the fields you answer. It never reads, speaks, stores or sends the contents of password, OTP, PIN or CVV fields.",
  ],
  [
    "Microphone",
    "Audio is captured only while a voice session is running; a notification shows that VoiceControl is listening. Speech is converted to text by your phone's speech recognition service. VoiceControl does not record or keep audio.",
  ],
  [
    "What is sent to our servers",
    "If you sign in and do not use local-only mode, the field labels of the current screen and the text of what you said are sent to the VoiceControl server to understand your answer, and the screen structure is used to find your saved flows. Values of sensitive fields are never sent. With the screenshot fallback turned on, a screenshot is sent only for apps that expose no readable fields.",
  ],
  [
    "AI providers",
    "The server may use an AI provider such as Anthropic or OpenAI to interpret speech and screenshots. Your phone never contacts them directly. Requests are processed under the providers' API terms and are not used for advertising.",
  ],
  [
    "What is stored",
    "Saved flows store field labels, questions and your edits, never the values you typed unless you add a default yourself. History stores what happened to each field (filled, skipped, typed by you) without values. Your profile stores only the details you enter. Signed-in phones are listed with their name, app version and last-seen time; remote-run logs record step labels and outcomes, never values. Passwords are stored as bcrypt hashes. Each signed-in browser or phone keeps a session with a short description of it (e.g. “Chrome on Windows”, from its User-Agent), when it signed in and when it was last used, so you can review and end sessions under Profile → Security; it is deleted when the session ends.",
  ],
  [
    "Organizations",
    "Flows you move into an organization are visible to all its members; editors and admins can change them and members' phones use them. Organizations store their name, members' email, name and role, pending invitations, and an audit log of who changed which flow, member, API key or webhook. Admins can create API keys (only a hash is stored) and webhooks that send flow and run events — ids, names, app and status, never spoken or typed values — to an address they choose.",
  ],
  [
    "Retention and deletion",
    "You can delete flows, history and your profile at any time in the app or this dashboard. Download all your data as JSON from Profile (dashboard) or Settings → Export my data (app). Delete my account (password required) erases your account and data immediately; backups are overwritten within 30 days. Signing out removes tokens from the phone.",
  ],
  [
    "Crash reports",
    "Only if you turn on “Send crash reports”, the app sends the error type, code location, app and Android version and phone model when it crashes. Numbers and email addresses are removed before sending and again on the server. Reports are deleted with your account.",
  ],
  [
    "Security",
    "All traffic uses HTTPS. Tokens on the phone are encrypted with the Android Keystore; tokens in the dashboard are kept in httpOnly cookies. With the app lock on, VoiceControl asks for your fingerprint, face or screen lock and hides its screens from screenshots.",
  ],
  ["Children", "VoiceControl is not directed at children under 13 and does not knowingly collect their data."],
  ["Contact", "privacy@voicecontrol.app"],
];

export function PrivacyPage() {
  return (
    <main className="mx-auto max-w-3xl p-6 md:p-10">
      <Link to="/" className="text-sm text-muted-foreground hover:underline">
        ← VoiceControl
      </Link>
      <h1 className="mb-2 mt-4 text-3xl font-semibold">Privacy policy</h1>
      <p className="mb-8 text-sm text-muted-foreground">Last updated 1 October 2026</p>
      <div className="grid gap-6">
        {SECTIONS.map(([title, body]) => (
          <section key={title}>
            <h2 className="mb-1 text-lg font-semibold">{title}</h2>
            <p className="leading-relaxed text-muted-foreground">{body}</p>
          </section>
        ))}
      </div>
    </main>
  );
}
