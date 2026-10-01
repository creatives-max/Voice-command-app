/**
 * The flow expression language (conditions, computed values, `{var}` question templates).
 *
 * Same grammar and semantics as the phone (`android/core/engine/.../expr/Expressions.kt`) and the
 * backend validator; all three run the shared vectors in `docs/spec/expressions.json`.
 */

export type Value = string | number | boolean | null;
export type Lookup = (name: string) => Value | undefined;

export class ExpressionError extends Error {
  constructor(
    message: string,
    readonly position: number,
  ) {
    super(message);
  }
}

type Node =
  | { t: "lit"; v: Value }
  | { t: "var"; name: string }
  | { t: "un"; op: string; e: Node }
  | { t: "bin"; op: string; l: Node; r: Node }
  | { t: "call"; name: string; args: Node[] };

/** Function name → allowed argument counts ([min, max]; null = any). */
export const FUNCTIONS: Record<string, [number, number] | null> = {
  upper: [1, 1], lower: [1, 1], trim: [1, 1], len: [1, 1], empty: [1, 1],
  contains: [2, 2], startsWith: [2, 2], endsWith: [2, 2], concat: null,
  digits: [1, 1], left: [2, 2], right: [2, 2], round: [1, 1], number: [1, 1],
  if: [3, 3], yes: [1, 1], today: [0, 0], year: [0, 0],
};

const YES_WORDS = new Set([
  "yes", "y", "yeah", "yep", "haan", "han", "ha", "haa", "ji", "hanji", "haanji", "ok", "okay", "sure", "true", "1",
  "हाँ", "हां", "हा", "जी", "होय", "ஆம்", "అవును", "হ্যাঁ", "হ্যা", "હા",
]);

/** True for "yes" in English, Hindi, Hinglish and the other supported Indian languages. */
export const isYesWord = (s: string) => YES_WORDS.has(s.trim().toLowerCase());

type Kind = "NUMBER" | "STRING" | "IDENT" | "OP" | "LPAREN" | "RPAREN" | "COMMA" | "END";
interface Token {
  kind: Kind;
  text: string;
  pos: number;
}

const DIGIT = /\p{Nd}/u;
const LETTER = /\p{L}/u;
const LETTER_OR_DIGIT = /[\p{L}\p{Nd}]/u;
const NUMERIC = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

function lex(s: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < s.length) {
    const c = s[i]!;
    if (/\s/.test(c)) {
      i++;
    } else if (DIGIT.test(c) || (c === "." && i + 1 < s.length && DIGIT.test(s[i + 1]!))) {
      const start = i;
      while (i < s.length && (DIGIT.test(s[i]!) || s[i] === ".")) i++;
      out.push({ kind: "NUMBER", text: s.slice(start, i), pos: start });
    } else if (c === "'" || c === '"') {
      const start = i;
      let sb = "";
      i++;
      while (i < s.length && s[i] !== c) {
        if (s[i] === "\\" && i + 1 < s.length) {
          sb += s[i + 1];
          i += 2;
        } else {
          sb += s[i];
          i++;
        }
      }
      if (i >= s.length) throw new ExpressionError("Unterminated string", start);
      i++;
      out.push({ kind: "STRING", text: sb, pos: start });
    } else if (LETTER.test(c) || c === "_") {
      const start = i;
      while (i < s.length && (LETTER_OR_DIGIT.test(s[i]!) || s[i] === "_" || s[i] === ".")) i++;
      out.push({ kind: "IDENT", text: s.slice(start, i), pos: start });
    } else if (c === "(") {
      out.push({ kind: "LPAREN", text: "(", pos: i++ });
    } else if (c === ")") {
      out.push({ kind: "RPAREN", text: ")", pos: i++ });
    } else if (c === ",") {
      out.push({ kind: "COMMA", text: ",", pos: i++ });
    } else {
      const two = s.slice(i, i + 2);
      if (["==", "!=", "<=", ">=", "&&", "||"].includes(two)) {
        out.push({ kind: "OP", text: two, pos: i });
        i += 2;
      } else if ("+-*/%<>!".includes(c)) {
        out.push({ kind: "OP", text: c, pos: i++ });
      } else {
        throw new ExpressionError(`Unexpected character '${c}'`, i);
      }
    }
  }
  out.push({ kind: "END", text: "", pos: s.length });
  return out;
}

class Parser {
  private p = 0;
  constructor(
    private readonly t: Token[],
    private readonly source: string,
  ) {}

  private peek = () => this.t[this.p]!;
  private next = () => this.t[this.p++]!;
  private isOp(...ops: string[]) {
    const tok = this.peek();
    return (tok.kind === "OP" || tok.kind === "IDENT") && ops.includes(tok.text);
  }

  parseAll(): Node {
    if (!this.source.trim()) throw new ExpressionError("Expression is empty", 0);
    const n = this.or();
    if (this.peek().kind !== "END") throw new ExpressionError(`Unexpected token '${this.peek().text}'`, this.peek().pos);
    return n;
  }

  private or(): Node {
    let n = this.and();
    while (this.isOp("||", "or")) {
      this.next();
      n = { t: "bin", op: "||", l: n, r: this.and() };
    }
    return n;
  }

  private and(): Node {
    let n = this.not();
    while (this.isOp("&&", "and")) {
      this.next();
      n = { t: "bin", op: "&&", l: n, r: this.not() };
    }
    return n;
  }

  private not(): Node {
    if (this.isOp("!", "not")) {
      this.next();
      return { t: "un", op: "!", e: this.not() };
    }
    return this.cmp();
  }

  private cmp(): Node {
    const n = this.add();
    if (this.isOp("==", "!=", "<", "<=", ">", ">=")) {
      const op = this.next().text;
      return { t: "bin", op, l: n, r: this.add() };
    }
    return n;
  }

  private add(): Node {
    let n = this.mul();
    while (this.isOp("+", "-")) {
      const op = this.next().text;
      n = { t: "bin", op, l: n, r: this.mul() };
    }
    return n;
  }

  private mul(): Node {
    let n = this.unary();
    while (this.isOp("*", "/", "%")) {
      const op = this.next().text;
      n = { t: "bin", op, l: n, r: this.unary() };
    }
    return n;
  }

  private unary(): Node {
    if (this.isOp("-")) {
      this.next();
      return { t: "un", op: "-", e: this.unary() };
    }
    return this.primary();
  }

  private primary(): Node {
    const tok = this.next();
    switch (tok.kind) {
      case "NUMBER": {
        if (!NUMERIC.test(tok.text)) throw new ExpressionError(`Bad number ${tok.text}`, tok.pos);
        return { t: "lit", v: Number(tok.text) };
      }
      case "STRING":
        return { t: "lit", v: tok.text };
      case "LPAREN": {
        const n = this.or();
        if (this.next().kind !== "RPAREN") throw new ExpressionError("Missing )", tok.pos);
        return n;
      }
      case "IDENT":
        if (tok.text === "true") return { t: "lit", v: true };
        if (tok.text === "false") return { t: "lit", v: false };
        if (tok.text === "null") return { t: "lit", v: null };
        return this.peek().kind === "LPAREN" ? this.call(tok) : { t: "var", name: tok.text };
      case "END":
        throw new ExpressionError("Unexpected end of expression", tok.pos);
      default:
        throw new ExpressionError(`Unexpected token '${tok.text}'`, tok.pos);
    }
  }

  private call(name: Token): Node {
    this.next(); // (
    const args: Node[] = [];
    if (this.peek().kind !== "RPAREN") {
      args.push(this.or());
      while (this.peek().kind === "COMMA") {
        this.next();
        args.push(this.or());
      }
    }
    if (this.next().kind !== "RPAREN") throw new ExpressionError(`Missing ) after arguments of ${name.text}`, name.pos);
    if (!(name.text in FUNCTIONS)) throw new ExpressionError(`Unknown function ${name.text}`, name.pos);
    const allowed = FUNCTIONS[name.text];
    if (allowed && (args.length < allowed[0] || args.length > allowed[1])) {
      const range = allowed[0] === allowed[1] ? `${allowed[0]}` : `${allowed[0]}-${allowed[1]}`;
      throw new ExpressionError(`${name.text}() takes ${range} argument(s)`, name.pos);
    }
    return { t: "call", name: name.text, args };
  }
}

export function parse(source: string): Node {
  return new Parser(lex(source), source).parseAll();
}

/** Returns null when valid, otherwise a human-readable error. */
export function validate(source: string): string | null {
  try {
    parse(source);
    return null;
  } catch (e) {
    if (e instanceof ExpressionError) return e.message;
    throw e;
  }
}

/** Variable names an expression reads. */
export function variables(source: string): Set<string> {
  const out = new Set<string>();
  const walk = (n: Node) => {
    if (n.t === "var") out.add(n.name);
    else if (n.t === "un") walk(n.e);
    else if (n.t === "bin") {
      walk(n.l);
      walk(n.r);
    } else if (n.t === "call") n.args.forEach(walk);
  };
  walk(parse(source));
  return out;
}

// -------------------------------------------------------------------------------------------------
// Value semantics

export function toNumber(v: Value | undefined): number | null {
  if (typeof v === "number") return v;
  if (typeof v === "string") {
    const s = v.trim();
    return s && NUMERIC.test(s) ? Number(s) : null;
  }
  return null;
}

export function toText(v: Value | undefined): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") return Number.isInteger(v) && Math.abs(v) < 1e15 ? v.toFixed(0) : String(v);
  return String(v);
}

export function truthy(v: Value | undefined): boolean {
  if (v === null || v === undefined) return false;
  if (typeof v === "boolean") return v;
  if (typeof v === "number") return v !== 0;
  return !["", "false", "no", "0", "null"].includes(v.trim().toLowerCase());
}

function equal(a: Value, b: Value): boolean {
  const na = toNumber(a);
  const nb = toNumber(b);
  if (na !== null && nb !== null) return na === nb;
  return toText(a).trim().toLowerCase() === toText(b).trim().toLowerCase();
}

function compare(a: Value, b: Value): number {
  const na = toNumber(a);
  const nb = toNumber(b);
  if (na !== null && nb !== null) return na === nb ? 0 : na < nb ? -1 : 1;
  const sa = toText(a).trim().toLowerCase();
  const sb = toText(b).trim().toLowerCase();
  return sa === sb ? 0 : sa < sb ? -1 : 1;
}

const pad = (n: number) => String(n).padStart(2, "0");

function evalNode(n: Node, vars: Lookup, today: () => Date): Value {
  switch (n.t) {
    case "lit":
      return n.v;
    case "var":
      return vars(n.name) ?? null;
    case "un":
      return n.op === "-" ? -(toNumber(evalNode(n.e, vars, today)) ?? 0) : !truthy(evalNode(n.e, vars, today));
    case "bin": {
      if (n.op === "||") return truthy(evalNode(n.l, vars, today)) || truthy(evalNode(n.r, vars, today));
      if (n.op === "&&") return truthy(evalNode(n.l, vars, today)) && truthy(evalNode(n.r, vars, today));
      const l = evalNode(n.l, vars, today);
      const r = evalNode(n.r, vars, today);
      const num = (v: Value) => toNumber(v) ?? 0;
      switch (n.op) {
        case "==":
          return equal(l, r);
        case "!=":
          return !equal(l, r);
        case "<":
          return compare(l, r) < 0;
        case "<=":
          return compare(l, r) <= 0;
        case ">":
          return compare(l, r) > 0;
        case ">=":
          return compare(l, r) >= 0;
        case "+": {
          const nl = toNumber(l);
          const nr = toNumber(r);
          return nl !== null && nr !== null ? nl + nr : toText(l) + toText(r);
        }
        case "-":
          return num(l) - num(r);
        case "*":
          return num(l) * num(r);
        case "/":
          return num(r) === 0 ? null : num(l) / num(r);
        case "%":
          return num(r) === 0 ? null : num(l) % num(r);
        default:
          throw new Error(`unknown operator ${n.op}`);
      }
    }
    case "call":
      return call(n.name, n.args, vars, today);
  }
}

function call(name: string, argNodes: Node[], vars: Lookup, today: () => Date): Value {
  if (name === "if") {
    return truthy(evalNode(argNodes[0]!, vars, today)) ? evalNode(argNodes[1]!, vars, today) : evalNode(argNodes[2]!, vars, today);
  }
  const a = argNodes.map((x) => evalNode(x, vars, today));
  const s = (i: number) => toText(a[i]);
  const n = (i: number) => toNumber(a[i]) ?? 0;
  switch (name) {
    case "upper":
      return s(0).toUpperCase();
    case "lower":
      return s(0).toLowerCase();
    case "trim":
      return s(0).trim();
    case "len":
      return s(0).length;
    case "empty":
      return s(0).trim() === "";
    case "contains":
      return s(0).toLowerCase().includes(s(1).toLowerCase());
    case "startsWith":
      return s(0).toLowerCase().startsWith(s(1).toLowerCase());
    case "endsWith":
      return s(0).toLowerCase().endsWith(s(1).toLowerCase());
    case "concat":
      return a.map(toText).join("");
    case "digits":
      return s(0).replace(/[^0-9]/g, "");
    case "left":
      return s(0).slice(0, Math.max(0, Math.trunc(n(1))));
    case "right": {
      const k = Math.max(0, Math.trunc(n(1)));
      return k === 0 ? "" : s(0).slice(-k);
    }
    case "round":
      return Math.round(n(0));
    case "number":
      return toNumber(a[0]);
    case "yes":
      return isYesWord(s(0));
    case "today": {
      const d = today();
      return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
    }
    case "year":
      return today().getFullYear();
    default:
      throw new ExpressionError(`Unknown function ${name}`, 0);
  }
}

export function evaluate(source: string, vars: Lookup, today: () => Date = () => new Date()): Value {
  return evalNode(parse(source), vars, today);
}

export const evaluateBoolean = (source: string, vars: Lookup, today?: () => Date) => truthy(evaluate(source, vars, today));
export const evaluateText = (source: string, vars: Lookup, today?: () => Date) => toText(evaluate(source, vars, today));

/** Replaces `{name}` / `{expression}` placeholders; broken placeholders are left as written. */
export function template(text: string, vars: Lookup, today?: () => Date): string {
  return text.replace(/\{([^{}]+)\}/g, (whole, inner: string) => {
    try {
      return toText(evaluate(inner, vars, today));
    } catch {
      return whole;
    }
  });
}

/** Errors in `{…}` placeholders of a question. */
export function templateErrors(text: string): string[] {
  const errors: string[] = [];
  for (const m of text.matchAll(/\{([^{}]+)\}/g)) {
    const err = validate(m[1]!);
    if (err) errors.push(`{${m[1]}}: ${err}`);
  }
  return errors;
}
