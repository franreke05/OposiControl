#!/usr/bin/env bash
# CI-GRADLE-VERIF: control negativo FUNCIONAL del paso de deriva de ci.yml. En una COPIA temporal (git init propio, el repo
# real no se toca) corrompe un sha256 / quita un componente de gradle/verification-metadata.xml, ejecuta el MISMO comando de
# regeneracion de ci.yml (acotado a un grafo pequeno) y exige que `git diff --exit-code -- gradle/verification-metadata.xml`
# FALLE. Ademas comprueba que la copia SIN corromper deja el diff vacio (el control no es trivialmente rojo).
set -u
cd "$(dirname "$0")/.."
fail=0
ok() { echo "ok   $1"; }; ko() { echo "FAIL $1"; fail=1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git ls-files -z | tar --null -T - -cf - | tar -xf - -C "$tmp"
cd "$tmp"
git init -q . && git add -A >/dev/null 2>&1 && git -c user.email=a@b -c user.name=n commit -qm base >/dev/null 2>&1
M=gradle/verification-metadata.xml
regen() { ./gradlew -q --no-daemon --no-configuration-cache --write-verification-metadata sha256 :backend:ktor:dependencies --configuration runtimeClasspath >/dev/null 2>&1; }
drift() { git diff --exit-code -- "$M" >/dev/null 2>&1; }
mutate() { node -e '
const fs=require("fs");const [p,mode]=process.argv.slice(1);let s=fs.readFileSync(p,"utf8");
if(mode==="corrupt"){const i=s.indexOf("name=\"netty-handler-4.2.17.Final.pom\"");if(i<0)process.exit(2);
  const j=s.indexOf("value=\"",i)+7;s=s.slice(0,j)+(s[j]==="0"?"1":"0")+s.slice(j+1);}
else{const r=/[ \t]*<component group="io\.netty" name="netty-handler" version="4\.2\.17\.Final">[\s\S]*?<\/component>\r?\n/;
  if(!r.test(s))process.exit(2);s=s.replace(r,"");}
fs.writeFileSync(p,s)' "$M" "$1"; }
regen; drift && ok "copia intacta: regenerar deja diff vacio" || ko "copia intacta ya difiere (control invalido)"
git checkout -q -- "$M"
base=$(git rev-parse HEAD)
# La mutacion se COMMITEA: representa un fichero versionado corrupto/incompleto, y CI regenera sobre el fichero versionado.
commit_mutation() { git add -- "$M" && git -c user.email=a@b -c user.name=n commit -qm mutated >/dev/null 2>&1; }
mutate corrupt || ko "no se pudo preparar la corrupcion"
git diff --quiet -- "$M" && ko "la mutacion no cambio el fichero"
commit_mutation
regen; drift && ko "sha256 corrupto NO detectado por el diff" || ok "sha256 corrupto => git diff --exit-code falla"
git checkout -q "$base" -- "$M"
commit_mutation
mutate remove || ko "no se pudo preparar la eliminacion"
git diff --quiet -- "$M" && ko "la mutacion no cambio el fichero"
commit_mutation
regen; drift && ko "componente quitado NO detectado por el diff" || ok "componente quitado => git diff --exit-code falla"
exit $fail
