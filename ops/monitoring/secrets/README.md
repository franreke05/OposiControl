Crear aqui `metrics_token` (una linea, sin salto final) con el MISMO valor que
`METRICS_SCRAPE_TOKEN` del backend. El archivo esta ignorado por git.
Debe existir ANTES de `docker compose up` (si no, Docker crea un directorio).
Permisos (MASTER6-CICD-004): Prometheus corre como `nobody` (65534), asi que un `0600` del operador no seria legible. Usar este directorio
`0700` y el archivo `0644` (o `chown 65534` + `0400`); detalle y verificacion (target UP) en `ops/monitoring/README.md`.
