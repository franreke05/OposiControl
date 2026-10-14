// Guardia de STACK_LOCK (PHASE B, regla dura del propietario): todo script que escribe en el stack local compartido, reinicia
// Ktor, toca Docker o ejecuta suites que mutan usuarios/datos llama a requireStackLock() ANTES de cualquier mutacion.
//   - STACK_LOCK.md existe y su HOLDER no es STACK_HOLDER -> exit 3 sin hacer nada (nunca avisar y seguir).
//   - No se puede localizar el bloqueo (sin LOCALAPPDATA) -> exit 3 (sin bloqueo verificable no se escribe).
//   - Sin fichero de bloqueo -> se permite (nadie lo tiene; el procedimiento es crearlo antes con tu HOLDER).
// La ruta es FIJA (%LOCALAPPDATA%\OposiBotPhaseB\handoffs\STACK_LOCK.md): no hay variable para apuntarla a otro sitio.
import fs from 'node:fs';
import path from 'node:path';

export function stackLockFile() {
  const base = process.env.LOCALAPPDATA;
  return base ? path.join(base, 'OposiBotPhaseB', 'handoffs', 'STACK_LOCK.md') : null;
}

export function requireStackLock(action) {
  const file = stackLockFile();
  if (!file) {
    console.error(`STACK_LOCK: ${action} BLOQUEADO. Sin LOCALAPPDATA no se puede comprobar el bloqueo.`);
    process.exit(3);
  }
  if (!fs.existsSync(file)) return;
  const text = fs.readFileSync(file, 'utf8');
  const holder = /^\s*HOLDER:\s*(\S+)/m.exec(text)?.[1] ?? '(sin HOLDER)';
  const me = process.env.STACK_HOLDER;
  if (me && me === holder) return;
  console.error(`STACK_LOCK: ${action} BLOQUEADO. El stack lo tiene ${holder} (${file}); STACK_HOLDER=${me ?? '(no definido)'}.`);
  console.error(text.trim().split(/\r?\n/).slice(0, 6).join('\n'));
  process.exit(3);
}
