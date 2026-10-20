// Guard against the 2026-10-18 data loss (E1-V1-PORT-FIX.md): the local `db` container keeps PGDATA in its OWN writable layer (no volume, no bind mount),
// so any `docker compose up` that RECREATES it (changed port, env, image) silently destroys the database.
//
// guardDbRecreate() runs before up.mjs touches compose:
//   - no db container, or it has mounts (data is in a volume), or the compose config is unchanged -> nothing to do
//   - otherwise the container would be recreated while it holds data -> REFUSE (exit 4), unless `--allow-db-recreate` is given, in which case the
//     whole container is first saved with `docker commit` as image `<project>-db-backup:<timestamp>` (data inside; restore with
//     `docker run` of that image, see the handoff) and a plain SQL dump is attempted into .local/backups/.
//   - `--reset` is the explicit destructive path and is not guarded here.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

import { DB_CONTAINER as NAME, PROJECT } from './stack-config.mjs';

// NAME = `<project>-db-1` (stack-config.mjs): inspect/commit need the raw container name; everything else goes through `docker compose -p`.
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

export function guardDbRecreate({ composeArgs, local, argv = process.argv }) {
  let info;
  try {
    info = JSON.parse(docker('inspect', NAME))[0];
  } catch {
    return { guarded: false, reason: 'no db container yet' };
  }
  if ((info.Mounts ?? []).length > 0) return { guarded: false, reason: 'db data lives in a volume' };
  let wanted = null;
  try {
    wanted = docker('compose', ...composeArgs, 'config', '--hash', 'db').split(/\s+/).pop();
  } catch { /* older compose: fall back to the port comparison below */ }
  const current = info.Config?.Labels?.['com.docker.compose.config-hash'];
  const published = info.HostConfig?.PortBindings?.['5432/tcp']?.[0]?.HostPort;
  const desiredPort = process.env.LOCAL_DB_PORT || (fs.existsSync(path.join(local, 'ports.json')) ? String(JSON.parse(fs.readFileSync(path.join(local, 'ports.json'), 'utf8')).db) : null);
  const changed = wanted && current ? wanted !== current : desiredPort !== null && published !== undefined && String(published) !== desiredPort;
  if (!changed) return { guarded: false, reason: 'db container config unchanged' };

  if (!argv.includes('--allow-db-recreate')) {
    console.error(`DB GUARD: the db container has NO volume and compose would RECREATE it (published port ${published} -> ${desiredPort}, or config changed). That DESTROYS the local database.`);
    console.error('Nothing was changed. Re-run with --allow-db-recreate to snapshot it first (docker commit + SQL dump) and then recreate, or --reset to start from scratch on purpose.');
    process.exit(4);
  }
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  const image = `${PROJECT}-db-backup:${stamp}`;
  docker('commit', NAME, image);
  console.log(`DB GUARD: container saved as image ${image}`);
  try {
    if (info.State?.Running !== true) docker('start', NAME);
    fs.mkdirSync(path.join(local, 'backups'), { recursive: true });
    const dump = execFileSync('docker', ['exec', NAME, 'pg_dumpall', '-U', 'postgres', '-h', 'localhost'], { encoding: 'buffer', maxBuffer: 1 << 30 });
    fs.writeFileSync(path.join(local, 'backups', `db-${stamp}.sql`), dump);
    console.log(`DB GUARD: SQL dump written to ${path.join(local, 'backups', `db-${stamp}.sql`)}`);
  } catch (error) {
    console.warn('DB GUARD: SQL dump failed (the committed image still holds the data): ' + String(error.message).split('\n')[0]);
  }
  return { guarded: true, image };
}
