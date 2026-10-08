# Cloudflare: configuracion objetivo (RUNBOOK, nada aplicado)

Estado: `NOT_APPLIED`. Se ejecuta a mano, mas adelante, por una persona con acceso a la cuenta. Este documento no contiene credenciales ni ids de zona.
El host del API es `{API_HOST}` (historico: `api.oposibots.es`); no se inventan otros dominios. El origen es el Caddy de `ops/caddy/Caddyfile.production`.

## 1. DNS

| Tipo | Nombre | Contenido | Proxy |
|---|---|---|---|
| A (y AAAA si el host tiene IPv6) | `{API_HOST}` | IP publica del servidor de origen | **Proxied (nube naranja)** |

* Sin registros que expongan la IP de origen (ni `origin.`, ni MX/SPF que la revelen). TTL automatico al estar proxied.
* Tras activar el proxy, **rotar la IP de origen** si estuvo expuesta antes sin proxy (historico DNS).

## 2. TLS

* SSL/TLS mode: **Full (strict)**.
* Certificado de origen: **Cloudflare Origin CA** (15 anos) generado en SSL/TLS > Origin Server, SAN = `{API_HOST}`. Guardar `origin.pem` y `origin.key` en
  `/etc/oposibots/production/origin/` del servidor (root, `0600`/`0644`), montados en solo lectura en Caddy. Nunca en el repo.
* Minimum TLS Version: 1.2. TLS 1.3: on. Always Use HTTPS: on. HSTS: lo emite el origen (`Strict-Transport-Security` en el Caddyfile); no duplicarlo en el borde hasta decidir `includeSubDomains`/preload.
* Opcional posterior: **Authenticated Origin Pulls** (mTLS cliente del borde hacia Caddy). Anade defensa en profundidad frente a la regla de IP del punto 5; requiere `client_auth` en Caddy.

## 3. WAF base

* Managed rules: Cloudflare Managed Ruleset **on** (accion por defecto), OWASP Core Ruleset **on** con sensibilidad inicial *Medium*, accion **Log** la primera semana y luego *Block*.
  Vigilar falsos positivos en `/api/v1/admin/importaciones/*` y subidas multipart (cuerpos binarios grandes) y excluir esas rutas del OWASP si molestan.
* Bot Fight Mode: on. **No** activar desafios (Managed Challenge/JS) en `/v1/*` ni `/api/v1/app/*`: los clientes son apps nativas sin navegador y un desafio las rompe.
* Reglas personalizadas (Custom rules), en orden:
  1. Bloquear `http.request.uri.path in {"/metrics" "/admin/backend" "/admin/dev"}` (defensa doble: Caddy ya devuelve 404).
  2. Bloquear metodos fuera de `GET POST PUT PATCH DELETE OPTIONS HEAD`.
  3. Bloquear peticiones sin `User-Agent` a `/v1/auth/*` (accion Block; revisar contra los clientes reales antes).
  4. Panel admin: opcionalmente restringir `/api/v1/admin/*` y `/content/*` por pais o por Cloudflare Access (los equipos de administracion son pocos y conocidos).

## 4. Rate limiting gruesos (NO duplican el limitador de negocio)

El limitador de Ktor (Valkey) cuenta por cuenta/IP y devuelve 429 con semantica de contrato (`LOGIN 30/300 s por IP`, `REGISTER 6/3600 s`, etc.). Cloudflare solo corta el
volumen abusivo ANTES de llegar al origen, con umbrales muy por encima de los del negocio, para no producir 429 distintos del contrato en uso legitimo:

| Regla | Expresion | Umbral por IP | Accion |
|---|---|---|---|
| Auth | `starts_with(http.request.uri.path, "/v1/auth/")` | 120 peticiones / 1 min | Block 10 min |
| API app | `starts_with(http.request.uri.path, "/api/v1/app/")` o `/v1/` | 600 peticiones / 1 min | Block 1 min |
| Admin | `starts_with(http.request.uri.path, "/api/v1/admin/")` | 300 peticiones / 1 min | Block 1 min |
| Subidas | `http.request.method eq "POST" and http.request.uri.path contains "/importaciones/" or contains "/recursos/archivo" or contains "/portada"` | 30 peticiones / 1 min | Block 10 min |

Ajustar con datos reales del panel Analytics (todos los umbrales son marcadores). Detras de NAT de operador moviles las IP se comparten: por eso son altos y la accion es temporal.

## 5. Cierre del origen a Cloudflare

* Firewall del host (UFW/nftables o grupo de seguridad del proveedor): permitir **80/443 solo desde los rangos de Cloudflare** (https://www.cloudflare.com/ips-v4 y ips-v6); SSH solo desde IPs de administracion; el resto denegado.
* Los mismos rangos estan en `trusted_proxies` de `Caddyfile.production`. **Refrescar ambas listas a la vez** cuando Cloudflare publique cambios (revisar trimestralmente: `curl -s https://www.cloudflare.com/ips-v4; curl -s https://www.cloudflare.com/ips-v6`).
* Docker publica 80/443 saltandose UFW por defecto: aplicar las reglas en la cadena `DOCKER-USER` (o `nftables` `forward`), no solo en `INPUT`. Comprobar desde fuera de Cloudflare que `curl -k https://<ip-origen>/health/live` NO responde.
* Caddy ya ignora `CF-Connecting-IP` de pares que no son Cloudflare (`trusted_proxies` + `trusted_proxies_strict`), y reescribe `X-Forwarded-For` con la IP real.

## 6. Cache

* Sin cache en el borde para el API: regla de pagina `{API_HOST}/*` -> Cache Level: Bypass (las respuestas son autenticadas y dinamicas). Excepcion posterior, solo con cabeceras `Cache-Control` explicitas del backend, para el catalogo anonimo `/v1/especialidades` y `/v1/examenes*`.

## 7. Comprobacion tras aplicar (solo lectura)

```bash
curl -sI https://{API_HOST}/health/live                      # 200, cabecera cf-ray presente
curl -s -o /dev/null -w '%{http_code}\n' https://{API_HOST}/metrics               # 404
curl -s -o /dev/null -w '%{http_code}\n' https://{API_HOST}/admin/backend/restart # 404
curl -s -o /dev/null -w '%{http_code}\n' https://{API_HOST}/ruta-inexistente      # 404
curl -sI --connect-to {API_HOST}:443:<IP_ORIGEN>:443 https://{API_HOST}/health/live  # desde fuera de Cloudflare: debe fallar/timeout
```
