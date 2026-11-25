export type TextSegment = { text: string; href?: string };

const URL_RE = /(https?:\/\/[^\s<>"'`]+)/g;

export function tokenizeLinks(text: string): TextSegment[] {
  if (!text) return [];
  const parts: TextSegment[] = [];
  let last = 0;
  for (const match of text.matchAll(URL_RE)) {
    const index = match.index ?? 0;
    if (index > last) parts.push({ text: text.slice(last, index) });
    const raw = match[0];
    const trimmed = raw.replace(/[),.;!?]+$/, '');
    parts.push({ text: trimmed, href: trimmed });
    if (trimmed.length < raw.length) parts.push({ text: raw.slice(trimmed.length) });
    last = index + raw.length;
  }
  if (last < text.length) parts.push({ text: text.slice(last) });
  return parts;
}

export const COMMON_EMOJIS = [
  '😊', '👍', '🙏', '👋', '🎉', '😄', '😅', '🤔',
  '👌', '❤️', '🔥', '✅', '✨', '💡', '📎', '⏰',
];

const SOUND_KEY = 'newcrisp:sound';
let audioCtx: AudioContext | null = null;

export function isSoundEnabled(): boolean {
  try { return localStorage.getItem(SOUND_KEY) !== 'off'; }
  catch { return true; }
}

export function setSoundEnabled(enabled: boolean): void {
  try { localStorage.setItem(SOUND_KEY, enabled ? 'on' : 'off'); }
  catch { /* ignore storage restrictions */ }
}

export function playNotificationSound(): void {
  if (!isSoundEnabled() || typeof window === 'undefined') return;
  try {
    const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    if (!audioCtx) audioCtx = new Ctx();
    if (audioCtx.state === 'suspended') void audioCtx.resume();
    const now = audioCtx.currentTime;
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, now);
    osc.frequency.exponentialRampToValueAtTime(1174.66, now + 0.08);
    gain.gain.setValueAtTime(0.001, now);
    gain.gain.exponentialRampToValueAtTime(0.08, now + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.22);
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start(now);
    osc.stop(now + 0.24);
  } catch { /* audio may be blocked before user gesture */ }
}
