// Generates ops/caddy/Caddyfile.hostcaddy from ops/caddy/Caddyfile.production for the Caddy that runs as a SYSTEM SERVICE
// on the production host (Caddy 2.6.x, automatic certificates, no proxy in front). Same routing; only what the older
// Caddy and the host topology require is changed. Usage: node scripts/gen-hostcaddy.mjs
import fs from 'fs'

const read = p => fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n')
const code = text => text.split('\n').filter(l => l.trim() && !l.trim().startsWith('#'))

const lines = code(read('ops/caddy/Caddyfile.production'))
const start = lines.findIndex(l => l.startsWith('{$API_HOST}'))
if (start < 0) throw new Error('site block not found in Caddyfile.production')
let site = lines.slice(start).join('\n') + '\n'

const bodyLimit = code(read('ops/caddy/body-limit.caddy')).map(l => '\t' + l.replace(/^ {4}/, '\t')).join('\n')
const metricsBlock = code(read('ops/caddy/metrics-block.caddy')).map(l => '\t\t' + l.trim()).join('\n')

// Caddy 2.6 wants the log filter fields inside a `fields { }` block (newer versions accept them directly).
const log26 = [
  '\tlog {',
  '\t\toutput stdout',
  '\t\tformat filter {',
  '\t\t\twrap json',
  '\t\t\tfields {',
  '\t\t\t\trequest>headers>Authorization delete',
  '\t\t\t\trequest>headers>Cookie delete',
  '\t\t\t\trequest>headers>Proxy-Authorization delete',
  '\t\t\t\trequest>headers>X-Api-Key delete',
  '\t\t\t\trequest>headers>Apikey delete',
  '\t\t\t\tresp_headers>Set-Cookie delete',
  '\t\t\t\trequest>uri query {',
  '\t\t\t\t\tdelete token',
  '\t\t\t\t\tdelete access_token',
  '\t\t\t\t\tdelete refresh_token',
  '\t\t\t\t\tdelete code',
  '\t\t\t\t\tdelete apikey',
  '\t\t\t\t\tdelete password',
  '\t\t\t\t}',
  '\t\t\t}',
  '\t\t}',
  '\t}',
].join('\n')

const logStart = site.indexOf('\tlog {')
const encodeAt = site.indexOf('\tencode ')
if (logStart < 0 || encodeAt < logStart) throw new Error('log block not found where expected')
site = site.slice(0, logStart) + log26 + '\n' + site.slice(encodeAt)

site = site
  .replace('{$API_HOST} {', 'api.oposibots.es {')
  .replace(/^\ttls \/etc\/caddy\/origin\/.*\n/m, '') // the host Caddy keeps its automatic certificates
  .replace(/^\timport \/etc\/caddy\/body-limit\.caddy\n/m, bodyLimit + '\n')
  .replace(/^\t\timport \/etc\/caddy\/metrics-block\.caddy\n/m, metricsBlock + '\n')
  .replace(/ktor-control:8080/g, '127.0.0.1:18083')
  .replace(/ktor-bot-1:8080 ktor-bot-2:8080/g, '127.0.0.1:18081 127.0.0.1:18082')
  .replace(/\{client_ip\}/g, '{remote_host}') // {client_ip} exists from Caddy 2.7; there is no proxy in front today

const leftovers = site.match(/ktor-|\{client_ip\}|\bimport |origin\.pem/g)
if (leftovers) throw new Error('not adapted: ' + leftovers.join(','))

const head = [
  '# Caddyfile for the Caddy that runs as a SYSTEM SERVICE on the production host (Caddy 2.6.x, automatic certificates,',
  '# no Cloudflare in front). GENERATED from Caddyfile.production by scripts/gen-hostcaddy.mjs: same routing (control',
  '# matchers, bot pool, health, deny by default, /metrics and dev paths 404). Upstreams are the loopback ports published',
  '# by ops/deploy/compose.hostcaddy.override.yaml. Differences: no global trusted-proxy block and {remote_host} instead of',
  '# {client_ip} (both need Caddy >= 2.7 and a proxy in front); TLS stays automatic. Do not hand-edit: regenerate.',
  '',
].join('\n')
fs.writeFileSync('ops/caddy/Caddyfile.hostcaddy', head + site.trimEnd() + '\n')
console.log('written ops/caddy/Caddyfile.hostcaddy (' + (head + site).split('\n').length + ' lines)')
