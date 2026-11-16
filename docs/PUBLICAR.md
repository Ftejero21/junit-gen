# Publicar junit-gen (notas para el mantenedor)

## Usuario de GitHub

El usuario de GitHub aparece como el marcador `__USUARIO_GITHUB__`. Sustitúyelo por el real antes del primer push (sin contar este archivo, que lo menciona como referencia):

| Archivo | Apariciones |
|---|---|
| `README.md` | 2 |
| `docs/MANUAL.md` | 3 |
| `plugin/.claude-plugin/plugin.json` | 2 (`repository` y `homepage`) |
| `plugin/README.md` | 1 |

Total: 8. Para comprobar que no queda ninguno: `git grep -n __USUARIO_GITHUB__ -- . ':!docs/PUBLICAR.md'`.

## Publicar una versión

1. Compila: `mvn -DskipTests package` (o `mvn clean package` para pasar también los tests de la herramienta).
2. Copia `target/junit-gen.jar` a `plugin/junit-gen.jar`. El plugin de Claude Code y los scripts `instalar-copilot.*` usan ese JAR; los hooks del plugin ejecutan una clase suya (`com.junitgen.seguridad.GuardaTanda`), así que tiene que ser el recién compilado.
3. Si cambió la skill, copia `src/main/resources/skills/generartest/SKILL.md` a `plugin/skills/generartest/SKILL.md` **conservando las dos líneas de frontmatter propias del plugin** (justo después de `argument-hint`):
   ```
   allowed-tools: "mcp__plugin_junit-gen_junit-gen__*"
   disallowed-tools: Bash, PowerShell, Monitor, Edit, Write, NotebookEdit
   ```
   La skill fuente (la que copian los scripts de Copilot) no lleva esas líneas.
4. La primera vez, marca el script de `sh` como ejecutable en git: `git update-index --chmod=+x instalar-copilot.sh`.
5. Haz commit y push. El plugin y la entrada del marketplace no declaran `version`, así que Claude Code usa el commit como versión y los usuarios reciben cada push.

Comprobación opcional del plugin y del marketplace: `claude plugin validate .` y `claude plugin validate ./plugin`.
