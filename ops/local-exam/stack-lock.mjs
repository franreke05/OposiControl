// Guardia de STACK_LOCK (PHASE B): toda escritura en el stack local compartido (up.mjs, run-ktor.mjs, e2e.mjs) llama a
// requireStackLock() ANTES de tocar nada. Si el fichero de bloqueo existe y su HOLDER no es quien ejecuta, sale con codigo 3:
// nunca imprime y continua.
//   STACK_LOCK_FILE  ruta del bloqueo (por defecto %LOCALAPPDATA%\OposiBotPhaseB\handoffs\STACK_LOCK.md)
//   STACK_HOLDER     quien ejecuta (p. ej. E1); obligatorio si el bloqueo existe
// Sin fichero de bloqueo no hay a quien molestar: se permite (stack de un solo desarrollador).
import fs from 'node:fs';
import path from 'node:path';

export function stackLockFile() {
  if (process.env.STACK_LOCK_FILE) return process.env.STACK_LOCK_FILE;
  const base = process.env.LOCALAPPDATA;
  return base ? path.join(base, 'OposiBotPhaseB', 'handoffs', 'STACK_LOCK.md') : null;
}

export function requireStackLock(action) {
  const file = stackLockFile();
  if (!file || !fs.existsSync(file)) return;
  const text = fs.readFileSync(file, 'utf8');
  const holder = /^\s*HOLDER:\s*(\S+)/m.exec(text)?.[1] ?? '(sin HOLDER)';
  const me = process.env.STACK_HOLDER;
  if (me && me === holder) return;
  console.error(`STACK_LOCK: ${action} BLOQUEADO. El stack lo tiene ${holder} (${file}); STACK_HOLDER=${me ?? '(no definido)'}.`);
  console.error(text.trim().split(/\r?\n/).slice(0, 6).join('\n'));
  process.exit(3);
}
