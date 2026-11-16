# junit-gen — Manual

[← Volver al README](../README.md)

Generador de tests unitarios JUnit para cualquier proyecto Java, asistido por IA y **de solo lectura** sobre el proyecto analizado.

## Qué es y cómo se reparten el trabajo la IA y la herramienta

- **La IA (Copilot u otra) solo decide QUÉ probar y CON QUÉ DATOS.** Deduce el estilo de los tests existentes y propone casos (escenario, datos, stubs, resultado esperado, aserciones y verificaciones) en un **spec JSON**.
- **junit-gen hace todo lo demás:** escanea el proyecto, valida el spec, descarta duplicados, escribe los `.java` con una plantilla uniforme adaptada a las versiones del proyecto y, si se pide, los compila y ejecuta.
- La IA **nunca** escribe archivos `.java` de test. Regenerar a partir del `spec.json` no consume IA.

Funciona en dos modos con el mismo JAR:

1. **Servidor MCP por stdio** (modo principal): Copilot en modo Agent o Claude Code usan sus herramientas. Sigue el estándar MCP, sin funciones exclusivas de VS Code (también vale para IntelliJ, Eclipse y Visual Studio).
2. **Línea de comandos** (`scan`, `detail`, `validate`, `generate`, `verify`, `resolve`): alternativa si MCP está bloqueado; cualquier IA genera el spec con el prompt incluido.

## Garantías de solo lectura

- **junit-gen nunca modifica nada dentro del proyecto analizado.** Solo escribe en la carpeta de salida que indiques (y, al verificar, en la caché normal de Maven/Gradle).
- **Única excepción:** si eliges *Establecer como ruta por defecto*, junit-gen guarda esa ruta en `rutas-por-defecto.json` de la **carpeta de instalación** (junto a `junit-gen.jar`) o, si se indica una carpeta de datos (`-Djunitgen.datos` o `JUNITGEN_DATOS`, como hace el plugin de Claude Code), en esa carpeta; nunca en el proyecto. El plugin de Claude Code escribe además en esa misma carpeta de datos los marcadores de la [protección durante la tanda](#protección-durante-la-tanda). Antes de escribir comprueba que esa carpeta real no está dentro del proyecto analizado y que el archivo no es un enlace ni una carpeta; si no puede guardar, lo indica como nota sin interrumpir nada. Si el archivo está corrupto se ignora (aviso en stderr). La ruta guardada se revalida siempre antes de usarla (si ya no es válida se ignora con una nota visible).
- Para ofrecer *Crear carpeta en el Escritorio*, en Windows junit-gen lee la carpeta real del Escritorio del registro con una **consulta fija de solo lectura** (`%SystemRoot%\System32\reg.exe query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders" /v Desktop`), invocada por ruta absoluta (nunca por nombre ni por PATH), con stdin cerrado, timeout de 5 s y salida limitada a 64 KB. Si falla, usa `%USERPROFILE%\Desktop`, `Escritorio` u OneDrive. La carpeta no se crea al elegirla: se crea al generar el primer test.
- Al empezar muestra: `Modo solo lectura sobre [proyecto]. Los archivos se crearán únicamente en [salida]`. En MCP es la primera línea de cada resultado.
- La ruta de salida se normaliza (absoluta, sin `..`, con symlinks, junctions y nombres 8.3 resueltos) y se **rechaza** si es igual al proyecto, está dentro de él o lo contiene, o si algún segmento es un archivo o un acceso directo `.lnk` (indica la carpeta real).
- Todas las escrituras de tests pasan por un único punto (`EscritorSeguro`): primero se planifican y validan todas las rutas y después se escribe, recomprobando antes de cada escritura que la carpeta real está dentro de la salida y fuera del proyecto.
- Solo se borra dentro de `<salida>\.junit-gen-tmp` (temporales de la verificación), sin seguir enlaces. Los tests generados solo se amplían; eliminar un caso exige confirmación explícita.
- La verificación nunca ejecuta `mvnw`, `gradlew`, fases Maven (`compile`, `test`, `clean`, `install`…) ni git. Maven solo se usa con `maven-dependency-plugin:3.8.1:build-classpath` sobre un **pom espejo** copiado al temporal (el proyecto nunca es su basedir) y con `:get` para descargar el lanzador de JUnit a la caché de Maven.
- Si el proyecto está compilado, sus clases (`target\classes`) se usan solo en lectura; si no, se compila en el temporal. Los tests se compilan y ejecutan en el temporal, con el directorio de trabajo y `java.io.tmpdir` dentro de él.
- Se toma una instantánea del módulo (ruta, tamaño y fecha) antes y después de ejecutar los tests; si cambia algo, el resultado y el informe incluyen una **alerta de integridad**.

## Instalación

**Requisito:** Java 17 o superior (**JDK**) en el `PATH`. junit-gen no incluye ningún JDK; si prefieres uno portátil, indica su ruta absoluta en `command` (ver [Configuración MCP](#configuración-mcp)).

Hay tres formas de instalarlo; las dos primeras parten de este repositorio, que trae el JAR ya compilado en `plugin/junit-gen.jar`.

### Claude Code (plugin)

```
/plugin marketplace add Ftejero21/junit-gen
/plugin install junit-gen@junit-gen
```

El plugin registra el servidor MCP, la skill `/generartest` y la protección durante la tanda. Detalles en [Claude Code (plugin)](#claude-code-plugin).

### GitHub Copilot en VS Code (script)

```bash
git clone https://github.com/Ftejero21/junit-gen.git
cd junit-gen
./instalar-copilot.sh
```

En Windows:

```
powershell -ExecutionPolicy Bypass -File .\instalar-copilot.ps1
```

El script:

1. Comprueba que hay Java 17 o superior (`java` del `PATH` o, si no, `JAVA_HOME`). El instalador se ejecuta con el lanzador de código fuente de Java, así que hace falta un **JDK** (no solo un JRE).
2. Registra el servidor `junit-gen` en los **dos** archivos que lee Copilot (ver [Configuración MCP](#configuración-mcp)): `~/.copilot/mcp-config.json` (runtime nuevo de agentes, clave `mcpServers`) y el `mcp.json` de usuario de VS Code (clave `servers`), con `"command": "java"` (o la ruta absoluta si Java sale de `JAVA_HOME`) y la ruta absoluta de `plugin/junit-gen.jar` del repositorio. Conserva los demás servidores y claves; si el archivo no se puede interpretar, no lo toca.
3. Copia la skill a `~/.copilot/skills/generartest/SKILL.md`.

Es idempotente: si no hay nada que cambiar, no escribe. Antes de modificar un archivo deja una copia `<archivo>.bak`. No descarga nada. Parámetros opcionales para usar otras rutas: `-McpJson <archivo>` y `-CopilotDir <carpeta>` en PowerShell; `--vscode-mcp <archivo>` y `--copilot-dir <carpeta>` en `sh`.

Después: reinicia VS Code, pon el chat en modo **Agent** y escribe `/generartest`. Si mueves la carpeta del repositorio, vuelve a ejecutar el script.

### Instalación manual (cualquier cliente MCP)

Carpeta de instalación de ejemplo: `C:\herramientas\junit-gen` en Windows o `~/herramientas/junit-gen` en macOS y Linux.

```
junit-gen\
  junit-gen.jar
  prompts\prompt-sin-mcp.md
  skills\generartest\SKILL.md     comando /generartest para el chat de Copilot (runtime nuevo de agentes)
  prompts\GenerarTest.prompt.md   comando /GenerarTest para VS Code con el runtime antiguo de Copilot
  rutas-por-defecto.json    ruta de exportación por defecto de cada proyecto (solo si guardas alguna)
  junit-gen.cmd             lanzador de la CLI (opcional)
  junit-gen-mcp.cmd         lanzador del servidor MCP (opcional)
```

`junit-gen.jar` es `plugin/junit-gen.jar` de este repositorio (o el que compiles); `SKILL.md`, `GenerarTest.prompt.md` y `prompt-sin-mcp.md` están en `src/main/resources/`.

- `junit-gen.cmd`:
  ```
  @echo off
  chcp 65001 >nul
  java -Dfile.encoding=UTF-8 -jar "%~dp0junit-gen.jar" %*
  ```
- `junit-gen-mcp.cmd` (sin `chcp` ni `echo`, para que stdout solo lleve JSON-RPC):
  ```
  java -Dfile.encoding=UTF-8 -jar "%~dp0junit-gen.jar" mcp
  ```

En macOS y Linux basta con `java -Dfile.encoding=UTF-8 -jar ~/herramientas/junit-gen/junit-gen.jar …`. Si quieres usar un JDK concreto (por ejemplo, uno portátil) en lugar del `java` del `PATH`, pon su ruta absoluta (`…\jdk\bin\java.exe`) en los lanzadores y en `command`.

Para reinstalar, sustituye `junit-gen.jar`, los prompts y la skill (también la copia de `~/.copilot/skills/generartest`). `rutas-por-defecto.json` no se toca, así que las rutas por defecto sobreviven a la reinstalación. Si el JAR está en uso (el cliente tiene el servidor MCP arrancado), copia el nuevo como `junit-gen.jar.nuevo`, cierra el cliente y renómbralo.

### Compilar desde el código

```bash
mvn -DskipTests package    # genera target/junit-gen.jar
```

Copia `target/junit-gen.jar` a `plugin/junit-gen.jar` para que lo usen el plugin y los scripts de instalación. `mvn clean package` (sin `-DskipTests`) ejecuta además los tests de la herramienta.

## Configuración MCP

El servidor se arranca con `java -Dfile.encoding=UTF-8 -jar <ruta>/junit-gen.jar mcp`. El mismo bloque sirve para todos los clientes; en los ejemplos, `java` es el del `PATH` (puedes poner la ruta absoluta de un JDK) y el JAR está en `C:\herramientas\junit-gen`. El script `instalar-copilot` configura las dos variantes de VS Code apuntando al JAR del repositorio.

### VS Code (Copilot) — runtime nuevo de agentes

Las versiones recientes de Copilot en VS Code usan un runtime de agentes que lee los servidores MCP de `~/.copilot/mcp-config.json` (en Windows, `%USERPROFILE%\.copilot\mcp-config.json`; clave `mcpServers`), compartido entre perfiles y espacios de trabajo:

```json
{
  "mcpServers": {
    "junit-gen": {
      "type": "stdio",
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "C:\\herramientas\\junit-gen\\junit-gen.jar", "mcp"],
      "tools": ["*"]
    }
  }
}
```

Si ya lo tenías en `mcp.json`, VS Code lo ofrece en *Agent Customizations → Migrations → MCP Servers (User) → Migrate*. Tras migrar, VS Code pide volver a confiar en el servidor. El servidor arranca al enviar el primer mensaje del chat: si pulsas *Start Server* sin chat abierto, el log muestra "Cannot start an MCP server before the session runtime has been created".

### VS Code (Copilot) — runtime antiguo

Archivo de usuario `mcp.json` de VS Code (Windows: `%APPDATA%\Code\User\mcp.json`; macOS: `~/Library/Application Support/Code/User/mcp.json`; Linux: `~/.config/Code/User/mcp.json`), clave `servers`:

```json
{
  "servers": {
    "junit-gen": {
      "type": "stdio",
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "C:\\herramientas\\junit-gen\\junit-gen.jar", "mcp"]
    }
  }
}
```

### Comando /generartest en VS Code (skill, runtime nuevo)

`skills\generartest\SKILL.md` es una *skill* de Copilot (opcional: el servidor MCP funciona igual sin ella; en IntelliJ/Eclipse se usa el prompt MCP `GenerarTest`). El script `instalar-copilot` la instala; a mano, copia la carpeta a `~/.copilot/skills/`:

```
xcopy /E /I "C:\herramientas\junit-gen\skills\generartest" "%USERPROFILE%\.copilot\skills\generartest"
```

En el chat de Copilot escribe `/` y aparece **`/generartest`** (el formato de skills exige el nombre en minúsculas). Lanza el flujo completo con las herramientas de junit-gen sin preguntarte rutas ni módulo. Lo que escribas después son los servicios a tratar (`/generartest UsuarioService`), un número de servicios N (`/generartest 3`: los 3 primeros servicios por orden alfabético de nombre de clase; la IA elige sus funciones por importancia de negocio con `seleccionar_funciones` dentro del cupo de junit-gen), funciones concretas de un servicio (`/generartest pedidoService funciones=crear,cancelar`), la palabra `todas` (`/generartest usuarioService todas`, `/generartest 3 todas`: el 100 % de las funciones) o la palabra `ruta` (`/generartest ruta`, para cambiar la carpeta de exportación). La skill lleva `disable-model-invocation: true`: solo se ejecuta cuando la invocas tú.

### Comando /GenerarTest en VS Code (prompt file, runtime antiguo)

`prompts\GenerarTest.prompt.md` es un *prompt file* de usuario de VS Code para el runtime antiguo de Copilot. Con el runtime nuevo, VS Code pide convertirlo en skill (*Migrations → Convert Prompt to Skills*); usa en su lugar la skill de arriba. Para instalarlo:

1. Crea la carpeta `%APPDATA%\Code\User\prompts\` si no existe.
2. Copia en ella `prompts\GenerarTest.prompt.md` de la carpeta de instalación:
   ```
   mkdir "%APPDATA%\Code\User\prompts"
   copy "C:\herramientas\junit-gen\prompts\GenerarTest.prompt.md" "%APPDATA%\Code\User\prompts\"
   ```
3. En el chat de Copilot escribe `/` y aparece **`/GenerarTest`**. Lanza el flujo completo en modo Agent con las herramientas de junit-gen; no hace falta indicar rutas. Lo que escribas después (`/GenerarTest UsuarioService`) son los servicios a tratar.

El archivo usa `tools: ['junit-gen/*']`, así que el servidor debe llamarse exactamente `junit-gen` en `mcp.json`. Si `/GenerarTest` no aparece, recarga la ventana (*Developer: Reload Window*) o reinicia el servidor (*MCP: List Servers → junit-gen → Restart Server*).

### IntelliJ IDEA (plugin GitHub Copilot)

Archivo `%LOCALAPPDATA%\github-copilot\intellij\mcp.json`, con la misma forma (clave `servers`). La ruta puede variar según la versión del plugin: lo más fiable es abrirlo desde el icono de MCP (herramientas) del chat de Copilot en modo Agent → *Add more tools* / *Edit config*.

### Eclipse (plugin GitHub Copilot)

*Preferences → GitHub Copilot → MCP*: pega el mismo JSON (clave `servers`).

### Visual Studio

Archivo `%USERPROFILE%\.mcp.json` con el mismo contenido (clave `servers`).

## Claude Code (plugin)

Este repositorio es también un *marketplace* de plugins de Claude Code:

```
/plugin marketplace add Ftejero21/junit-gen
/plugin install junit-gen@junit-gen
```

- **Comando**: el nombre completo es `/junit-gen:generartest` (por ejemplo, `/junit-gen:generartest genérame los tests de 2 servicios`). El corto `/generartest` también funciona mientras ningún otro comando o skill use ese nombre. Acepta los mismos argumentos que en Copilot.
- **Servidor MCP**: se arranca con el `java` del `PATH` (Java 17+; el plugin no incluye JDK) y el JAR del plugin. Sus herramientas se llaman `mcp__plugin_junit-gen_junit-gen__*`.
- **Permisos**: durante el turno en que invocas `/generartest`, la skill preaprueba las herramientas de junit-gen. Para no tener que aprobarlas en turnos posteriores (por ejemplo, `continúa la tanda` sin el comando), añade esta regla a tu `settings.json` de Claude Code (opcional):
  ```json
  {
    "permissions": {
      "allow": ["mcp__plugin_junit-gen_junit-gen__*"]
    }
  }
  ```
- **Ruta por defecto**: `rutas-por-defecto.json` vive en la carpeta de datos del plugin (`~/.claude/plugins/data/<id-del-plugin>/`), que se conserva entre actualizaciones; el servidor la recibe con `-Djunitgen.datos`. Fuera del plugin se sigue guardando junto al JAR.
- **Actualizaciones**: el plugin no fija versión, así que Claude Code ofrece cada cambio publicado en el repositorio.

### Protección durante la tanda

En Claude Code el plugin impide además que el agente edite archivos o ejecute comandos mientras trabaja con junit-gen:

1. **Dos capas.** La skill del plugin quita a la IA las herramientas `Bash`, `PowerShell`, `Monitor`, `Edit`, `Write` y `NotebookEdit` durante el turno en que invocas `/generartest` (capa nativa, sin estado ni coste). Además, dos hooks `PreToolUse` del plugin cubren el caso general.
2. **Cuándo se activa y cuándo se libera.** El criterio es «la IA ha usado junit-gen en este turno»: la primera llamada a una herramienta de junit-gen anota la sesión y el mensaje del usuario en curso; desde ahí, cualquier `Bash`, `PowerShell`, `Edit`, `Write`, `MultiEdit` o `NotebookEdit` de la misma sesión en ese mismo turno se bloquea con un mensaje que le dice a la IA que siga con junit-gen. El siguiente mensaje del usuario libera el bloqueo solo, sin hooks de inicio ni de fin de turno. Si la versión de Claude Code no informa del mensaje en curso, el bloqueo caduca a los 10 minutos de la última llamada a junit-gen.
3. **Dónde se anota.** Lo anota el propio hook (una clase del JAR, `com.junitgen.seguridad.GuardaTanda`) en `tandas/` de la carpeta de datos del plugin: un archivo pequeño por sesión, que se borra pasadas 24 h. Nunca escribe en el proyecto ni en stdout.
4. **Coste.** Los hooks se ejecutan con `java` directamente (sin shell, igual en Windows, macOS y Linux): cada `Bash`, `Edit` o `Write` de cualquier sesión con el plugin activado arranca antes una JVM (décimas de segundo).
5. **Fallo abierto.** Si el hook no puede ejecutarse (sin Java en el `PATH`, error interno), no bloquea nada: es defensa en profundidad sobre las garantías de solo lectura de junit-gen, y cerrarlo rompería todas tus sesiones si Java desapareciera del `PATH`.
6. **Qué no bloquea.** Las lecturas (`Read`, `Grep`, `Glob`) y las demás herramientas MCP siguen disponibles.

Para desactivarlo (junto con el resto del plugin): `/plugin disable junit-gen@junit-gen`.

## Uso paso a paso con Copilot Agent

1. Abre el chat de Copilot en **modo Agent** y comprueba que las herramientas de `junit-gen` están activas.
2. Lanza el flujo **sin indicar rutas**: en VS Code escribe **`/generartest`** (skill; con el runtime antiguo, **`/GenerarTest`**), opcionalmente seguido de argumentos; en IntelliJ/Eclipse usa el prompt MCP **`GenerarTest`** (sus argumentos `rutaProyecto`, `carpetaSalida`, `servicios`, `todas`, `funciones` y `continuar` son opcionales).
   - **Argumentos**: servicios (`/generartest usuarioService, PedidoService` → las funciones que elige la IA por importancia de negocio con `seleccionar_funciones`, dentro del cupo de junit-gen), un **número de servicios** N (`/generartest 3` → `numServicios`: los 3 primeros servicios por orden alfabético de nombre de clase, con la misma elección de funciones; N nunca es un número de tests), ambos (`/generartest 2 a b c` → los 2 primeros por orden alfabético de los nombrados), **`funciones=a,b`** junto a UN servicio (`/generartest pedidoService funciones=crear,cancelar` → `escanear_proyecto` con `servicios` y `funciones`: se prueban exactamente esas, origen `usuario`, y la IA no llama a `seleccionar_funciones`; para varios servicios, un comando por servicio), la palabra **`todas`** junto a lo anterior (`/generartest usuarioService todas`, `/generartest 3 todas` → `todas: true`: el 100 % de las funciones públicas, incluidas las triviales; `todas` nunca es un nombre de servicio), la palabra **`continuar`** sola (`/generartest continuar` → `escanear_proyecto {continuar: true}`: retoma la tanda guardada en `tanda.json` por el siguiente servicio, también en un chat nuevo) o la palabra **`ruta`** (`/generartest ruta` → `cambiar_ruta_salida`: cambia la carpeta de exportación y, si no pediste nada más, termina). Sin argumentos, el agente pregunta exactamente «¿Cuántos servicios (N) o qué servicios quieres probar? junit-gen elegirá sus funciones más relevantes (añade `todas` para probarlas todas)».
   - **Frases libres**: también puedes escribirlo con tus palabras: `/generartest generame los tests para 10 servicios`, `/generartest genera tests de diez servicios`, `/generartest "tests de PedidoService, UsuarioService y FacturaService"`, `/generartest prueba todas las funciones de usuarioService`, `/generartest haz tests de pedidoService solo de crear y cancelar`, `/generartest continúa la tanda`, `/generartest cambia la carpeta de salida`. El agente pasa el texto tal cual en `peticion` y es junit-gen (no la IA) quien lo interpreta de forma determinista: número en cifra o en letra → `numServicios`; nombres que coinciden con servicios del proyecto (sin distinguir mayúsculas, interfaz → implementaciones) → `servicios`; «todas (las funciones)» → `todas`; métodos reales del servicio tras «funciones», «métodos» o «solo (de)» → `funciones`; «continúa»/«sigue» → `continuar`; lo demás es relleno. Comillas, comas, «y», mayúsculas y acentos no importan. Si un nombre parece un servicio pero no existe, se avisa con nombres parecidos y se sigue con el resto; si no se deduce nada, junit-gen hace preguntar «¿Cuántos servicios (N) o qué servicios quieres probar?…». Si llegan además argumentos estructurados, mandan ellos campo a campo. No admite exclusiones («menos…», «excepto…»): lo que sigue se ignora con un aviso.
   - **Proyecto**: no se pregunta. El agente pasa en `ruta` la raíz del workspace que ya conoce (nunca un módulo). Si no la pasa, junit-gen usa, por este orden, la carpeta abierta en el IDE (MCP *roots*, si el cliente los declara), el último escaneo de la sesión y el **directorio de trabajo del servidor** (VS Code lo arranca en la carpeta del workspace), siempre que contenga algún módulo Maven/Gradle y no sea la carpeta del usuario o un ancestro suyo, la carpeta de instalación, una raíz de unidad ni una carpeta del sistema. Si hay varias carpetas abiertas en el IDE, el agente te pregunta cuál.
   - **Módulo y servicios**: tampoco se pregunta el módulo. Con `servicios`, junit-gen busca cada nombre en todos los módulos por niveles (gana el primero con resultado): nombre simple o FQN exacto; igual sin distinguir mayúsculas; y, para nombres simples, con o sin el sufijo `Impl`. Si coincide una **interfaz**, se sustituye por **todas sus implementaciones** sin preguntar (`/generartest usuarioService` → `UsuarioServiceImpl`). La tabla «Servicios pedidos» indica la coincidencia de cada uno y junit-gen deduce el módulo de trabajo («Módulo de trabajo deducido: pedidos (de N módulos)»). Solo es **ambiguo** si quedan varios grupos distintos (una interfaz y su `Impl` son uno solo): entonces se muestran los candidatos (`módulo:FQN`) y el agente te pregunta cuál. Un nombre no encontrado (o una interfaz sin implementación) se indica y se sigue con el resto. Sin ambigüedad no se pide confirmación de la selección.
   - **Plan de generación (`numServicios` N, 1 a 200, o servicios nombrados)**: junit-gen calcula un **plan determinista por servicios** (en el resumen, una fila por servicio # | Servicio | Módulo | Métodos | Casos | Sin tests | Selección y el total de servicios, métodos y casos; los casos por método llegan en `casosPlan` de `detalle_servicio`). El plan es **provisional** mientras un servicio tenga «Selección» `pendiente`: se recalcula para ese servicio cuando la IA llama a `seleccionar_funciones` (`IA (guardada)` = elección de una sesión anterior reutilizada; `usuario` = funciones nombradas; `todas` = 100 %). Si cabe, el resumen incluye las «Funciones candidatas» del primer servicio pendiente. Con N toma los N primeros servicios de todos los módulos (o los N primeros de los nombrados) por orden alfabético del nombre simple de la clase (sin distinguir mayúsculas; desempate por nombre exacto, FQN y módulo). Con `orden: prioridad` en `junit-gen.yml` se usa el orden por prioridad: más métodos sin tests, más ramas, menos tests existentes, montaje más sencillo, FQN y módulo. El pie del plan indica el orden usado. Se descartan con aviso los que requieren refactor o no tienen funciones seleccionables (solo triviales); si hay menos de N, lo avisa. De cada servicio se planifican sus **funciones seleccionadas** (ver «Selección de funciones»), también las que ya tienen tests (estado `con tests`: solo escenarios nuevos). Casos por método: `base = 1 + min(ramas, 2)`; sin tests, `base`; con tests, `max(1, base − tests existentes)`, acotado por el máximo de casos que le queda a la función (`maxCasos` menos los casos ya generados en la salida); las funciones que ya están en su máximo no se planifican. Un servicio se completa cuando están cubiertas sus funciones seleccionadas. El agente lo muestra y lo genera **sin pedir confirmación**; `generar_tests` devuelve el progreso `plan {total, generados, restantes, servicios, metodos {total, completos}, pendientes, siguiente, instruccion}` (`siguiente` = FQN del siguiente servicio con casos pendientes; la instrucción nombra el siguiente paso exacto; las listas de servicios completados y pendientes van en `resumenSesion.plan`) (casos acreditados por método con tope en lo previsto; un caso descartado porque ya está generado en la carpeta de salida —duplicado de nivel 3— cuenta siempre como atendido, aunque el método no tuviera tests; en métodos que ya tenían tests, cualquier otro descarte por duplicado también cuenta como atendido).
   - <a id="seleccion-de-funciones"></a>**Selección de funciones** (QUÉ funciones probar lo elige la IA por importancia de negocio; junit-gen prepara los datos, valida y controla todo lo demás):
     - **Quién elige**: la IA, con `seleccionar_funciones`, antes de leer cuerpos. Sin `funciones`, la herramienta devuelve las «Funciones candidatas» (todas las públicas no triviales, paginadas, sin cuerpos): `clave → retorno | p=puntuación | escribe | tx | lanza: … | privados: … | tests: N`. La IA prioriza lo que más daño haría si fallara (altas/bajas/modificaciones, reglas y validaciones, permisos y cálculos), da un motivo de 1 frase por función y pone en `omitidas`, con motivo, toda función que escribe datos y deja fuera. Si el usuario nombró funciones (`funciones=a,b`), mandan las suyas (origen `usuario`) y la IA no decide; con `todas`, el 100 % sin selección.
     - **Puntuación (orientativa y para la selección automática)**: `propia(m) = 2·ramas + 2·try + catch + 2·min(llamadas a dependencias, 6) + 3·min(llamadas con efecto —save, delete, update, send…—, 3) + 3 si @Transactional + min(aritmética, lambdas, referencias y new, 5) + min(sentencias/5, 4)`; `p(m) = propia(m) + Σ propia(q)` de los métodos **no públicos** de la clase que `m` llama (sin scope o con `this.`, todas las sobrecargas) hasta 3 niveles, sin repetir; las ramas de esos privados también cuentan para los casos del plan. `escribe` = alguna llamada a una dependencia `save|delete|remove|update|insert|persist|merge|flush…` en `m` o en sus privados; `tx` = `@Transactional`; `lanza` = tipos de `throw new X(…)`.
     - **Triviales** (no seleccionables salvo `todas` o si las nombra el usuario): getters/setters, `toString`/`equals`/`hashCode`, vacías, una línea sin lógica y delegación directa a una dependencia, a otro método público o a un privado sin lógica. Delegar en un privado con lógica **no** es trivial.
     - **Cupo**: `N` = públicas no triviales; `cupo = max(1, ⌈N · porcentajeFunciones / 100⌉)` (60 % por defecto, `porcentajeFunciones` en `junit-gen.yml`) y `mínimo = max(1, ⌈cupo / 2⌉)`. junit-gen valida la elección: `FUNCION_DESCONOCIDA`, `FUNCION_TRIVIAL`, `FALTA_MOTIVO`, `EXCESO_SELECCION`, `SELECCION_INSUFICIENTE`, `FALTA_MOTIVO_OMISION`, `SELECCION_VACIA`, `SIN_FUNCIONES`, `SELECCION_INVALIDA` (más de 200 entradas).
     - **Sin elección (plan B)**: si la IA no llama a `seleccionar_funciones`, junit-gen usa su selección automática (top % por puntuación, origen `java`) y no rechaza nada; `detalle_servicio` lo señala en `seleccion.origen`/`nota`.
     - **Persistencia**: la selección vale para la sesión (por proyecto y servicio) y se guarda en `spec.json` (`servicios[].seleccion`) al generar. La de origen `ia` se reutiliza en sesiones nuevas («IA (guardada)») y no la pisan las de origen `usuario`, `java` ni `todas`; la de `usuario` solo vale para la petición en curso.
     - **Orígenes**: `ia`, `usuario`, `java` (automática) y `todas`.
     - Cada función tiene un **máximo de casos** según su puntuación (`maxCasos`: ≤3 → 2; 4–7 → 3; 8–13 → 4; ≥14 → 5), que fija junit-gen. `detalle_servicio` solo lista las funciones elegidas; `validar_spec` y `generar_tests` rechazan casos nuevos sobre las demás (`FUNCION_NO_SELECCIONADA`), por encima del máximo (`EXCESO_CASOS`) o sin comentario (`FALTA_COMENTARIO`); los casos ya generados siguen siendo válidos. `informe.md` incluye el apartado «Funciones que se han probado y por qué»: por servicio, cuántas se prueban, quién las eligió y el límite, y una tabla con cada función (si se prueba o no) y su motivo.
   - **Carpeta de salida**: si guardaste una **ruta por defecto** válida para el proyecto, junit-gen la usa **sin preguntar** y lo indica con una nota («Ruta por defecto de MiProyecto: … (para cambiarla: /generartest ruta)»). Si no, pregunta **una sola vez por sesión y proyecto** «¿Dónde exportamos los tests?» con:
     - **Crear carpeta en el Escritorio** → `<Escritorio>\<NombreProyecto>Test` (p. ej. `C:\Users\<tu-usuario>\Desktop\MiProyectoTest`; se crea al generar);
     - **Ruta por defecto: …** (si hay una guardada);
     - **Otra ruta…**: solo si la eliges aparece una segunda pantalla con un único campo para escribir la ruta completa.

     Es una sola pantalla con una única lista (cada pantalla tiene una sola propiedad). Elijas lo que elijas, una pregunta posterior ofrece **[Establecer como ruta por defecto] [Solo esta vez]** (rechazarla equivale a solo esta vez). Para cambiarla después, **`/generartest ruta`** pregunta **[Mantener la ruta por defecto: …]** (si no hay, la del Escritorio) **[Otra ruta…]** con el mismo patrón; si escribes otra, **[Solo esta vez] [Establecer como ruta por defecto]**.

     Toda ruta se valida (nunca dentro del proyecto ni relativa; si se rechaza, se vuelve a pedir el texto con el motivo, hasta 3 rechazos; una ruta guardada que ya no es válida se ignora con una nota) y se recuerda el resto de la sesión, así que las demás herramientas no necesitan rutas.
   - Si tu cliente no soporta *elicitation* (o la rechazas), el agente te hace las mismas preguntas en el chat.
3. El agente llama a `escanear_proyecto` y te muestra el **resumen**: servicios pedidos, plan provisional (una fila por servicio, con la columna «Selección»), «Funciones candidatas» del primer servicio pendiente si caben, versiones y estilo sugerido por módulo de trabajo, nº de advertencias y el siguiente paso. Las secciones largas (plan con métodos y progreso, servicios, estilo con extractos, avisos, refactor, dudosos, descartes) se piden aparte con `seccion` + `pagina`, solo si las quieres; con `seccion` no se recalcula ni se reinicia el plan. Solo si hay ambigüedad (o no indicaste ni N ni servicios) te pregunta.
4. Para CADA servicio, en **2-3 llamadas** y **sin ejecutar nada entre servicios**: (1) la IA elige las funciones de «Funciones candidatas» (en el resumen del escaneo para el primero o en `siguienteServicio.candidatas` de `generar_tests` para los demás; si no vienen, `seleccionar_funciones {clase}`) y llama a `seleccionar_funciones {clase, funciones:[{nombre, motivo}], omitidas:[{nombre, motivo}]}`: su respuesta ya trae el **paquete de trabajo** del servicio (1 línea de confirmación + JSON con las funciones elegidas y su cuerpo, los métodos de la clase que llaman, `stubsTipicos`, tipos, helpers de otras clases del proyecto, `mocks`/`dependencias`/`montaje` y `plantillaSpec`); (2) solo si `paginacion.siguiente` no es null, `detalle_servicio` con esos argumentos (una vez: página 2 del paquete); si es null, no se llama a `detalle_servicio`; (3) escribe el spec y llama a `generar_tests`, que valida el spec (si devuelve `errores`, los corrige y repite; los duplicados los decide la herramienta; `validar_spec` es opcional). Con `todas`, `funciones=…` o «IA (guardada)»: `detalle_servicio {clase, paquete: 1}` → [página 2] → `generar_tests`. Si el cliente carga las herramientas bajo demanda (búsqueda de herramientas), el agente busca las de junit-gen una sola vez al empezar, no antes de cada llamada. El `detalle_servicio` clásico (sin `paquete`) va **paginado por métodos** y solo lista las funciones elegidas: la primera llamada trae la cabecera (montaje, dependencias, versiones, tests emparejados, `seleccion` y `plantillaSpec`) y los primeros métodos (varios por página: cuerpos compactados sin comentarios ni logs, claves vacías omitidas; las páginas siguientes solo traen la clase, los métodos, internos, tipos y `paginacion`); si `paginacion.siguiente` no es null, el agente vuelve a llamar con esos argumentos exactos (`desde`, y `parteCuerpo` para cuerpos largos servidos por partes) y puede generar tramo a tramo. Cada método trae `maxCasos`, `casosExistentes` y `casosGeneradosEnSalida` (si existen), `llama` (llamadas a dependencias que hace, `dep.metodo`) y, con plan, `casosPlan {previstos, faltan}`; cada página trae `stubsTipicos` (`{llamada, retorno, devuelve, nota}`: retorno y expresión sugerida de las llamadas a dependencias del proyecto; `<X>` = una instancia de X, ver `tiposAuxiliares[X].crear`; `nota: usuario autenticado` en las que devuelven el usuario actual); los tipos auxiliares llegan resumidos con `modo`/`crear`/`setters` (cómo instanciarlos: record, builder, setters, constructor o enum; completos con `tipos`) y la cabecera trae `recetas` (lo que junit-gen ya aplica solo, ver «Perfil del proyecto y recetas aprendidas»). Cada caso nuevo del spec lleva `queHaceOriginal` (qué hace la función original) y `queComprueba` (qué comprueba el test), 1 frase de máx. 160 caracteres cada uno. Con plan, junit-gen fija el cupo de casos por método y lo comunica como `metodo=N` en la línea `accionRequerida` (= `casosPlan.faltan`); la IA genera exactamente esos. Si un servicio queda incompleto, el siguiente paso es completar ESE servicio con los `metodo=N` que faltan: la **puerta de plan** rechaza empezar o pre-leer otro servicio del plan con `seleccionar_funciones` o `detalle_servicio` (también con `parteCuerpo: 1`; se abre sola tras 3 rechazos seguidos). **Salvaguarda anti-bucle**: tras 2 llamadas seguidas a `generar_tests` sin casos nuevos o 4 specs rechazados seguidos, junit-gen cierra el servicio atascado (`servicioCerrado`; sus casos pendientes se dan por atendidos y queda como incompleto) y pasa al siguiente, así que el plan siempre llega a `restantes = 0`. Las llamadas con `reemplazarCasos` o `eliminarCasos` no cuentan.
5. junit-gen **no pregunta si probar los tests**: `generar_tests` devuelve `verificacion.decision` = `probar` (decisión de toda la sesión, también con plan) y **encola la prueba de ese servicio en segundo plano** (un único hilo verificador, servicio a servicio): responde enseguida y el agente sigue con el siguiente servicio sin esperar ni sondear. Cada prueba compila solo el archivo de test del servicio y ejecuta solo sus clases en `<salida>\.junit-gen-tmp`, **sin tocar el proyecto**, reutilizando el classpath y las clases del proyecto preparados una vez por módulo.
   - **Aislamiento por caso**: si la clase de test no compila, los errores se atribuyen a cada caso por sus líneas (marcadores `junit-gen:caso-inicio/fin`) y se recompila una copia temporal sin los casos culpables: esos quedan `no_compila` con su propio mensaje y los demás se ejecutan y reciben su estado real (sin gastar intentos). Solo un error fuera de cualquier caso (cabecera, imports, campos, montaje común) afecta a todos. Cada error de compilación trae `pista` con las firmas reales de constructores y métodos del tipo (por reflexión sobre el classpath de verificación, sin inicializar clases; si falla, del índice del proyecto) y el detalle de javac (`required/found/reason`). Si un import del test no compila (clase inexistente o paquete equivocado), junit-gen lo corrige solo antes de pasar el error a la IA: lo sustituye por la clase correcta del JDK, las librerías o el proyecto si hay un candidato claro (o lo quita) y recompila, hasta 3 rondas; queda anotado en «Arreglos al final» de `informe.md`. Al validar el spec se comprueba que cada import existe: uno inexistente con un candidato claro se corrige (aviso `IMPORT_CORREGIDO`) y, si no, se rechaza con `IMPORT_NO_EXISTE` y los candidatos.
   - **Arreglos al final** (por defecto, `arreglos: alFinal`): durante la generación no se corrige ni se bloquea nada. Cada caso que falla en segundo plano (no compila, error de montaje o fallo de aserción) se apunta en `tanda.json` con su servicio, clase de test, error resumido, pista e intentos; el agente solo ve un contador en la primera línea («NO HAS TERMINADO (servicio 24 de 50; 3 fallos apuntados para arreglar al final): …») y sigue con el siguiente servicio. `seleccionar_funciones` y `detalle_servicio` no se rechazan por fallos de otros servicios (la puerta de plan sigue igual).
   - **Nota y puerta de montaje** (solo con `arreglos: inmediato`): si un servicio anterior tiene casos que no compilan o fallan al montarse, cada respuesta lleva la nota de cabecera «Verificación en segundo plano: `X` tiene N casos… Corrígelos ahora: estado_verificacion {} trae errores y pistas.» (las cabeceras no llevan listas de ids: el detalle va en el JSON) y `seleccionar_funciones` y el inicio de `detalle_servicio` de **otro** servicio se rechazan una vez con un error que ya incluye errores, pistas e ids (`generar_tests` nunca se bloquea). Si el servicio roto se está volviendo a probar, la puerta espera hasta 30 s y nunca rechaza con datos viejos. Si el agente vuelve a intentar avanzar sin regenerar, o el intento es ≥ 2, los casos rotos se **apartan automáticamente** (`montaje_fallido`: su bloque queda comentado en el test con `// junit-gen:apartado motivo=…`, nunca se borra, y se anota en `informe.md`) y la tanda sigue. El agente corrige el spec (nunca el `.java`) y llama a `generar_tests` (valida el spec) con `reemplazarCasos` = esos ids; se vuelven a probar solos. Mientras se corrige un servicio no se le aplican recetas automáticas: el spec debe incluir en `construccion` todo lo que necesite. Si los tests de un servicio cambian mientras se prueban, el resultado se descarta y se repite.
   - **Fallos de aserción** (por defecto, `fallosAsercion: revisarIa`): **no se pregunta nada al usuario**. Por defecto se revisan en la fase de arreglo (`arreglar_caso` con `motivo` si el test estaba mal planteado, o `registrar_analisis_ia` con `bug_servicio`); solo con `arreglos: inmediato`, si un servicio tiene fallos de aserción, la siguiente respuesta lleva la nota «Verificación en segundo plano: `X` tiene N fallo(s) de aserción sin revisar. Revísalos ahora sin preguntar al usuario: estado_verificacion {}.» y la misma puerta impide empezar otro servicio (como máximo 2 rechazos seguidos; después deja pasar y quedan para el cierre). La IA llama a `estado_verificacion` (trae `revisar`, `reemplazar` y, por caso, su error y su `spec`), relee el código con `detalle_servicio {clase, metodos}` y registra con `registrar_analisis_ia` un veredicto por caso con un motivo basado en el código: `error_ia` (test mal planteado → corrige el spec y `generar_tests` con `reemplazarCasos`; se vuelve a probar solo, máximo 3 intentos) o `bug_servicio` (→ `posible_bug`, el test se mantiene y el motivo va a informe.md); `dudoso` solo si no puede decidir. Está prohibido poner como esperado el valor obtenido sin justificarlo con el código. El cierre con `ejecutar_tests` es la red de seguridad para lo que quede sin veredicto. Con `fallosAsercion: preguntar` se conserva la pregunta única al final de la tanda.
   - **Tanda persistente y `continuar`**: el progreso de la tanda se guarda en `<salida>\tanda.json` (lista ordenada de servicios, `indice` del primer servicio no completo, métodos con casos previstos, ids generados, descartados por duplicado y `enSalida` (descartados porque ya estaban en la salida), `renunciados` (casos dados por atendidos por la salvaguarda), `cierre` (motivo del cierre de un servicio atascado), y estado por servicio: `pendiente`, `generado`, `verificando`, `ok`, `con_fallos_montaje`, `con_fallos_asercion` o `montaje_fallido`, con sus intentos de montaje). Se actualiza al escanear con plan, al generar, al terminar cada prueba en segundo plano, al cerrar con `ejecutar_tests` (`cerrada: true` si no quedan correcciones ni fallos de aserción pendientes) y tras `resolver_fallo` o `registrar_analisis_ia`. Si el contexto del chat se agota o el servidor se reinicia, **`/generartest continuar`** en un chat nuevo llama a `escanear_proyecto {continuar: true}` (el agente también la llama por su cuenta si pierde el hilo tras un resumen del contexto): restaura el plan tal cual (sin recalcularlo), los casos generados que siguen en `spec.json` y sus estados (`pasa`; `fallo_asercion`, `posible_bug` o `dudoso` → fallo de aserción; `montaje_fallido` → error de montaje), vuelve a probar en segundo plano lo no verificado (con `verificacion: siempre`) y responde «Tanda reanudada» con el siguiente servicio y el siguiente paso, sin repetir lo ya generado. En el mismo proceso solo informa del progreso. Sin tanda pendiente (no existe, es de otro proyecto o está cerrada y completa) responde «No hay ninguna tanda pendiente…; usa /generartest N». Una selección `funciones=…` de la tanda original no se restaura: la IA elige las funciones de los servicios que faltan. Un escaneo nuevo con plan sustituye una tanda sin terminar (lo indica con una nota; los servicios ya generados no se repiten). `continuar` no se combina con `numServicios`, `servicios`, `funciones` ni `todas`. También guarda `fase` (`generacion` | `arreglo` | `cierre`), `arreglos` (cada caso apuntado con su error, pista, intentos, cambios y resultado) y `arreglosInformados`: `escanear_proyecto {continuar: true}` retoma también la fase de arreglo («Fase: arreglo (quedan P de T casos por arreglar)») y manda a `ejecutar_tests {}`.
   - Las verificaciones en segundo plano viven en memoria del servidor: si se reinicia, `continuar` (o el cierre) vuelve a probar lo que quede sin verificar. Se cambia con la clave `verificacion` de `junit-gen.yml`: `siempre` (por defecto), `preguntar` (una sola vez por sesión **[Probar tests] [Descartar]** por elicitation MCP, o en el chat si el cliente no la soporta; si caduca sin respuesta, `generar_tests` devuelve `pendiente_usuario` con `respuestaFinal` y `ejecutar_tests` repite la pregunta) o `nunca` (decisión `descartar`: no se ejecutan salvo que lo pidas). Las preguntas de junit-gen (carpeta de salida y, solo con `verificacion: preguntar` o `fallosAsercion: preguntar`, la de probar o la de fallos de aserción) caducan a los 150 s (menos de lo que tarda el cliente en cortar la llamada; propiedad de sistema Java `-Djunitgen.elicitacion.timeoutSegundos=N`, 60–600); si no respondes, junit-gen no decide por ti.
6. Cuando `plan.restantes` = 0 el agente cierra la tanda (salvo decisión `descartar`) con `ejecutar_tests` sin argumentos: espera a las pruebas en segundo plano como máximo 60 s por llamada (si no han terminado devuelve `{resumenSesion, verificando: true, progreso, accionRequerida}` sin `respuestaFinal` y el agente vuelve a llamarla), encola lo que quede sin verificar (no repite lo ya verificado), devuelve las correcciones de montaje pendientes y los fallos de aserción sin veredicto (en `decisiones` con `revisar_ia` y en `accionRequerida`, para que la IA los revise sin preguntar; solo con `fallosAsercion: preguntar` hace la pregunta única). Cuenta **solo los casos generados en esta sesión** y devuelve `resumenSesion`, `respuestaFinal` y, por test: `pasa`, `fallo_asercion`, `error_montaje` o `no_compila`, con `deEstaSesion`. Con `casos` fuerza la reejecución de esos ids; con `todos`, `clases` o `modulo` espera a la cola y ejecuta de forma síncrona como antes. Si al agente aún le queda trabajo (montaje reintentable, correcciones de montaje pendientes, fallos de aserción sin veredicto, cambios autorizados sin aplicar o casos del plan), la respuesta **no trae `respuestaFinal`**: empieza por `accionRequerida` («NO HAS TERMINADO: …» + el siguiente paso) y `resumenSesion.instruccion` lo recuerda; `respuestaFinal` solo aparece cuando no queda nada (con solo una pregunta de `fallosAsercion: preguntar` caducada se mantiene). Lo mismo en `paginaResultados`, `resolver_fallo`, `registrar_analisis_ia` y `generar_tests`. Además, mientras quede trabajo (con la configuración por defecto), toda respuesta de cualquier herramienta empieza por la línea `accionRequerida: NO HAS TERMINADO (servicio k de N): llama ahora a …` con la llamada exacta; con plan completo es `ejecutar_tests {}` y, cuando llega `respuestaFinal`, ya no hay cabecera.
   - **Fase de arreglo** (por defecto): con el plan completo y las pruebas terminadas, junit-gen corrige primero solo lo mecánico, sin IA (valores sin `eq(...)` mezclados con matchers, respuestas simuladas que el código no usa, auxiliar inexistente con un único dato declarado de ese tipo; máximo 2 rondas por caso, nunca toca `esperado`, `aserciones` ni `verificaciones`) y lo anota como «arreglado automáticamente». El resto llega agrupado por servicio, autosuficiente: `accionRequerida` «Fase de arreglo: casos k–j de M — servicio 23 de 50 `X`…» con la llamada exacta, `fase`, `casos` (cada uno con `id`, `error`, `pista`, `pistas` —origen de la excepción, estáticos no simulados, expected/actual con los `return` del método, símbolo inexistente con los datos disponibles—, `auto` y su `spec` actual sin textos) y `codigo` de sus métodos; con un solo caso, la forma de siempre (`caso` + `spec`, y `servicio` si el error está en la parte común). El agente los corrige todos en UNA llamada `arreglar_caso {casos: [{caso, spec, motivo}]}` (que reescribe esos casos, recompila y ejecuta la clase una vez y devuelve `resultadoAnterior.resultados` + los siguientes casos) o, si un fallo de aserción es un bug real del código, con `registrar_analisis_ia` y `bug_servicio`. No se llama a `detalle_servicio` en la fase de arreglo salvo que venga `codigo.masCodigo`. Una guardia de calidad rechaza los cambios que rebajan el test (`igual` → `contiene`/`noNulo`, aserción o verificación quitada, escenario valor ↔ excepción) sin un `motivo` que cite al menos dos nombres reales del código (`DEBILITA_TEST`) y el esperado copiado del valor obtenido sin justificar (`COPIA_SIN_JUSTIFICAR`); si se acepta, el caso queda marcado «test menos estricto» en `tanda.json`, `informe.md` y `respuestaFinal`. Nunca convierte un posible bug en un test que pasa. Los casos van agrupados por servicio y un error de la parte común se arregla una vez para todos. Máximo 3 intentos por caso: después se aparta solo (montaje o compilación: comentado en el test; aserción: `dudoso`). Sin casos pendientes, `ejecutar_tests {}` cierra con `respuestaFinal` y escribe la sección «Arreglos al final» de `informe.md`. Si tras 2 correcciones de la parte común (o 3 rechazos) la clase de test sigue sin compilar por un error común, junit-gen aparta todos los casos pendientes de ese servicio (sin tocar el archivo, anotados en `informe.md`) y sigue con el siguiente servicio.
   - **Puertas de `ejecutar_tests`**: rechaza sin ejecutar ni escribir nada (`isError`) si hay plan y en la sesión no se ha generado ningún caso («llama a detalle_servicio y generar_tests primero», con los servicios y métodos pendientes), si el plan está incompleto (salvo `parcial=true`, solo si tú lo pides; se recuerda el resto de la sesión), si no hay plan ni nada generado en la sesión, o si `carpetaTests` no es la carpeta de salida de la sesión (indica cuál es). `todos=true` (solo si pides verificar tests de sesiones anteriores) ejecuta toda la carpeta: esos resultados llevan `deEstaSesion=false` y no cuentan. Si hay más resultados de los que caben, `paginaResultados=2..` devuelve las páginas siguientes de la misma ejecución sin volver a ejecutar.
   - **`resumenSesion`** (en `generar_tests`, `ejecutar_tests` y en los rechazos): cifras de control calculadas por junit-gen —`carpeta`, `casosGenerados`, `ejecutados`, `pasan`, `fallosAsercion`, `erroresMontaje`, `noCompilan`, `desconocidos`, `sinEjecutar`, `descartadosPorDuplicado`, `eliminados` y `plan {serviciosTotal, serviciosCompletados, serviciosPendientes, casosTotal, casosGenerados, casosRestantes}`—; los tests de sesiones anteriores no cuentan.
   - **`respuestaFinal`** (en `ejecutar_tests`, `resolver_fallo`, `registrar_analisis_ia` y, solo si no se van a ejecutar los tests o la pregunta quedó sin responder, en `generar_tests`; siempre solo cuando no queda nada pendiente): el markdown final que construye junit-gen. El último mensaje del agente es **exactamente** ese texto, sin añadir, quitar ni cambiar nada. Formato:

     ```
     Se generaron y ejecutaron los tests de `UsuarioServiceImpl`.

     | Resultado | Cifra |
     |---|---|
     | Casos generados | 42 |
     | Ejecutados | 42 |
     | Pasan | 40 |
     | Fallos de aserción | 1 |
     | Errores de montaje | 1 |
     | No compilan | 0 |
     | Descartados por duplicado | 2 |
     | Arreglados al final | 1 |
     | Posibles bugs | 1 |
     | Apartados (no se pudieron arreglar) | 0 |
     | Servicios completados | `UsuarioServiceImpl` |
     | Servicios pendientes | Ninguno |

     Informe: [informe.md](file:///C:/ruta/salida/informe.md). Los tests están en `C:\ruta\salida`; copiarlos a `src/test/java` del proyecto es un paso manual.
     ```

     La primera línea es «Se generaron los tests de …» si no se ejecutó nada y «No se generaron tests nuevos.» si la sesión no generó casos; los servicios pendientes llevan lo que falta (`` `X` (faltan 3 casos en 2 métodos) ``), las listas vacías dicen «Ninguno» y se muestran como máximo 15 (luego «+N más»). Sin carpeta de salida, la última línea es «No hay carpeta de salida en esta sesión.» Las filas «Arreglados al final», «Posibles bugs» y «Apartados (no se pudieron arreglar)» (apartados incluye los `dudoso`) solo aparecen si algún caso falló durante la tanda.

     Si una pregunta quedó sin responder, la primera línea lo indica (la tabla y la última línea no cambian):
     - (solo con `verificacion: preguntar`) [Probar tests] [Descartar] sin responder y nada ejecutado: «Se generaron los tests de …; la ejecución quedó pendiente porque la pregunta [Probar tests] [Descartar] quedó sin responder. Pídeme que los ejecute cuando quieras.»
     - (solo con `fallosAsercion: preguntar`) Pregunta de fallos de aserción sin responder: «Se generaron y ejecutaron los tests de …; queda pendiente tu decisión sobre N fallo(s) de aserción porque la pregunta quedó sin responder. Dime qué hacer con ellos o pídeme que te lo vuelva a preguntar.» («1 fallo de aserción» / «N fallos de aserción»).
   - Errores de montaje o compilación (`montajeReintentables`; solo con `arreglos: inmediato`, por defecto van a la fase de arreglo): el agente corrige el **spec** (nunca el `.java`) y regenera con `reemplazarCasos` (máximo 3 intentos; después `montaje_fallido`: el caso se aparta solo, con su bloque comentado en el test y anotado en `informe.md`, nunca borrado). Con montaje y fallos de aserción a la vez, `accionRequerida` lleva las dos instrucciones (montaje primero); con `fallosAsercion: preguntar`, mientras queden casos de montaje reintentables no se pregunta por las aserciones.
   - **Guardia anti-maquillaje**: `generar_tests` rechaza sin escribir nada (`REQUIERE_DECISION_USUARIO`) reemplazar o eliminar un caso en `fallo_asercion`, `posible_bug` o `dudoso` sin autorización registrada en `spec.json` (`autorizacionCambio`): solo la da un veredicto `error_ia` registrado con `registrar_analisis_ia` (motivo basado en el código) o, con `fallosAsercion: preguntar`, *Ajustar lo esperado* (pregunta única o `resolver_fallo decision=ajustar` con `confirmadoPorUsuario=true`), y vale para un único reemplazo. Eliminar esos casos solo se hace con *Eliminar casos* o `resolver_fallo decision=eliminar` confirmado. Un caso protegido cuya clase deja de compilar conserva su estado y sigue pendiente.
   - Fallos de aserción con `fallosAsercion: preguntar` (`asercionesPendientes`; los ya marcados como `posible_bug` o `dudoso` no se vuelven a preguntar; se incluyen los pendientes de ejecuciones anteriores aún no preguntados en la sesión, aunque la ejecución use `casos`): pueden ser bugs reales. Al final, siempre (aunque fallen 1 o 2), **una única pregunta** con la lista (id, `Clase.metodoTest`, «esperado X, obtenido Y») y cinco opciones que se aplican a todos los casos: **[Es un bug, mantener los tests] [Ajustar lo esperado] [Eliminar casos] [Revisar con IA] [Revisar uno a uno en el chat]**. *Eliminar casos* cuenta como tu confirmación explícita. Si la pregunta caduca sin respuesta, los casos siguen en `fallo_asercion` (nada se ajusta ni se elimina) y se repite con `ejecutar_tests` + `casos` o se resuelve con `resolver_fallo` tras tu respuesta.
   - **Revisión de la IA** (por defecto, y con *Revisar con IA* en el modo `preguntar`): el agente lee el código de cada método (`detalle_servicio` con `metodos`), decide un veredicto con un motivo que cite el código — `error_ia` (el test estaba mal planteado), `bug_servicio` (el código contradice lo que la función debe hacer) o `dudoso` — y lo registra con `registrar_analisis_ia`. Con `error_ia` corrige el spec y regenera esos casos con `reemplazarCasos` (se vuelven a probar solos en segundo plano), como máximo 3 intentos por caso en total (contador compartido con el montaje); si vuelven a fallar aparecen en `revisionIaPendiente` y la IA repite el análisis sin preguntarte. `bug_servicio` deja el caso en `posible_bug` y `dudoso` en `dudoso`: en ambos se mantiene el test. El proyecto nunca se toca.
7. Al final, el agente muestra `respuestaFinal` tal cual. Revisa `informe.md`, escrito en lenguaje llano para que lo entienda cualquiera: cada apartado empieza con «En resumen: …» y los datos para programadores van al final como «Detalle técnico». Apartados: «Creación de tests» (con «Funciones que se han probado y por qué» y «Qué ha cambiado en los tests rehechos»), «Verificación: prueba de los tests», «Revisión de la IA» (test, conclusión, por qué y qué se hace ahora) y «Decisión sobre un test que falla». Las instrucciones para el agente no se escriben en el informe; el glosario de símbolos está en su cabecera («Cómo leer este informe»). Revisa también los tests en la carpeta de salida. Cada método de test generado lleva encima un comentario de hasta 4 líneas (`// Función: …` qué hace la función original y `// Test: …` qué comprueba), saneado por junit-gen.

Estados de un caso en `spec.json`: `generado`, `pasa`, `fallo_asercion`, `posible_bug` (bug del servicio: se mantiene el test), `dudoso` (la IA no pudo decidir o se agotaron los 3 intentos: se mantiene el test), `montaje_fallido` (apartado tras 3 intentos o al avanzar con el caso aún roto: queda comentado en el test con `// junit-gen:apartado motivo=no_compila|error_montaje`; un `generar_tests` con `reemplazarCasos` y contenido distinto lo reactiva) y `eliminado`.

Herramientas MCP (los argumentos entre corchetes son opcionales: por defecto se usan el proyecto deducido y la carpeta de salida elegida en la sesión):

- `escanear_proyecto([ruta], [servicios], [numServicios], [todas], [funciones], [carpetaSalida], [guardarRutaPorDefecto], [modulo], [filtro], [soloSinCubrir], [pagina], [tamPagina], [refrescar], [seccion], [continuar])`: devuelve un resumen breve; con `numServicios` (N = número de servicios) o con servicios resueltos, incluye el «Plan de generación» compacto. `seccion` (`plan`, `servicios`, `estilo`, `avisos`, `refactor`, `dudosos`, `descartes`) + `pagina` devuelve esa sección paginada sin recalcular el plan. `todas=true` (solo si el usuario escribió `todas`; también vale la palabra `todas` dentro de `servicios`) planifica el 100 % de las funciones, incluidas las triviales, y se recuerda para `detalle_servicio`, `validar_spec` y `generar_tests` de la sesión. `funciones` (solo si el usuario nombró funciones; exige exactamente un servicio resuelto en `servicios`) fija esas funciones con origen `usuario` antes de calcular el plan. `cantidad` ya no existe (error claro). `continuar=true` (si el usuario escribió `continuar` o si el agente perdió el hilo de la tanda en curso, y sin `numServicios`, `servicios`, `funciones` ni `todas`) retoma la tanda de `tanda.json` por el siguiente servicio.
- `seleccionar_funciones(clase, [funciones], [omitidas], [pagina], [ruta], [modulo])`: sin `funciones`, las «Funciones candidatas» del servicio (paginadas con `pagina`, con el cupo mínimo–máximo); con `funciones` (`[{nombre, motivo}]`; `nombre` = nombre, que cubre sus sobrecargas, o clave `nombre(Tipos)`) y `omitidas`, valida la elección de la IA, la guarda en la sesión, recalcula el plan del servicio y devuelve 1 línea de confirmación seguida del JSON del **paquete de trabajo** del servicio (todo lo necesario para escribir el spec; si `paginacion.siguiente` no es null, se pide una vez la página 2 con `detalle_servicio`; si es null, se llama directamente a `generar_tests`). Errores: `{valido:false, errores[{codigo, mensaje, sugerencia}], cupo, minimo, instruccion}`. No escribe archivos.
- `detalle_servicio(clase, [ruta], [modulo], [metodos], [desde], [tipos], [parteCuerpo], [paquete])`: con `paquete` (1 o 2) devuelve el **paquete de trabajo** del servicio (el mismo que `seleccionar_funciones` con elección): `clase`, `modulo`, `montaje`, `mocks` (dependencias `@Mock` sin nada especial, `nombre: Tipo`), `dependencias` (solo las que necesitan atención), `recetas`, `versiones`, `restriccionesJava`, `testsEmparejados`, `seleccion`, `plantillaSpec` (esqueleto de caso solo en el primer servicio de la sesión), `metodos` (funciones elegidas con su cuerpo, por partes si es largo), `metodosInternos` (métodos de la clase que llaman, profundidad ≤ 3), `stubsTipicos`, `tiposAuxiliares` (con campos, creación y `anidados`), `helpersExternos` (métodos estáticos o heredados de otras clases del proyecto que llaman), `omitido`/`cuerpoRecortado` + `ampliar` (lo que no cupo, declarado) y `paginacion {pagina, paginas, siguiente, instruccion}`; con `paquete: 2`, `desde` es el cursor de bloques que da `paginacion.siguiente` (como máximo una página extra). No se combina con `tipos` ni `parteCuerpo`. Sin `paquete` (zoom bajo demanda): `clase` admite el nombre simple (o una interfaz, que se resuelve a su implementación); si es ambiguo, se usa el módulo de trabajo deducido. Paginado por métodos (`paginacion {totalMetodos, desde, hasta, restantes, metodosRestantes (solo en la página con cabecera y sin `metodos`), siguiente, instruccion}`); ninguna página supera el presupuesto: si el primer método no cabe junto a la cabecera, la página 1 trae solo la cabecera (`hasta: 0`) y los métodos siguen con `paginacion.siguiente` (`{desde: 1, parteCuerpo: 1}` = métodos desde el primero, sin repetir la cabecera), solo con las funciones seleccionadas (con `metodos` explícitos, una función no seleccionada llega con `seleccionada:false` y su `motivo`); por método devuelve el cuerpo compactado, `maxCasos`, `casosExistentes` (tests del proyecto) y `casosGeneradosEnSalida` (casos ya generados en la carpeta de salida de la sesión) si existen y, con plan, `casosPlan`. Además: `metodos[].llama`, `stubsTipicos`, `tiposAuxiliares[].modo|crear|setters` (los tipos con fábrica omiten `notasLombok` y, salvo modo `constructor`, `constructores`) y `recetas` en la cabecera. `tipos` devuelve tipos auxiliares completos; `ampliar` explica cómo pedir lo resumido. Tras `seleccionar_funciones` con elección solo hace falta para `paginacion.siguiente`. No escribe archivos.
- `validar_spec(spec, [ruta], [carpetaSalida])`: opcional, `generar_tests` valida igual (aplica las mismas recetas). No escribe archivos.
- `generar_tests(spec, [carpetaSalida], [guardarRutaPorDefecto], [decisionVerificacion: probar|descartar], [reemplazarCasos], [eliminarCasos], [confirmadoPorUsuario])`: devuelve `verificacion.decision` (`probar`, `descartar`, `pendiente`, `pendiente_usuario` o `sin_tests`) (por defecto `probar`, sin preguntar; `pendiente_usuario` solo con `verificacion: preguntar`) con `alcance` = `sesion` y, con plan, `plan {total, generados, restantes, servicios, metodos, pendientes, siguiente, instruccion}`. Con `probar` encola la prueba de los servicios generados en segundo plano y responde enseguida. Error `REQUIERE_DECISION_USUARIO` si `reemplazarCasos`/`eliminarCasos` incluye un caso protegido sin autorización. Si no se van a ejecutar los tests o esa pregunta quedó sin responder, devuelve también `respuestaFinal`. Valida el spec antes de generar (con errores devuelve `errores` y no genera) y aplica las recetas aprendidas (avisos `RECETA_APLICADA`, o `RECETAS_NO_APLICADAS` si con ellas el spec sería inválido). Si quedan servicios en el plan y no hay correcciones de montaje pendientes, añade `siguienteServicio {clase, candidatas}` (las «Funciones candidatas» del siguiente servicio, si caben enteras) para elegir sus funciones sin otra llamada.
- `arreglar_caso([caso], [casos], [spec], [servicio], [motivo])`: solo en la fase de arreglo (cuando `accionRequerida` dice «Fase de arreglo»): corrige los casos que fallaron durante la tanda. `casos` = [{caso, spec, motivo}] (varios del mismo servicio, máximo 12) o un solo caso con `caso`, `spec` y `motivo`; `spec` = el caso corregido (completo o solo los campos que cambian; los demás se conservan); `servicio` {imports, construccion, fixtures} solo si el error está en la parte común; `motivo` obligatorio en fallos de aserción y si algo queda menos estricto (registra él mismo el veredicto `error_ia` tras pasar la guardia de calidad; sin justificación, `DEBILITA_TEST` o `COPIA_SIN_JUSTIFICAR`). Reescribe esos casos con una sola generación (`reemplazarCasos`), recompila y ejecuta una vez la clase de test de ese servicio, espera el resultado (hasta 60 s; si no, `verificando: true`) y devuelve `resultadoAnterior` (`{caso, resultado, detalle}` o `{resultados: […]}`; `arreglado` | `sigue_fallando` | `apartado` | `dudoso` | `posible_bug` | `no_pendiente` | `rechazado`) y los siguientes casos. Un caso rechazado no impide aplicar los demás. Máximo 3 intentos por caso; después lo aparta solo. `servicio` siempre se aplica: los `imports` recibidos sustituyen por nombre simple a los guardados, las `fixtures` con el mismo nombre se reescriben y un cambio solo en la parte común cuenta como intento; si nada cambia, el rechazo `SIN_CAMBIOS` explica qué parte enviar.
- `ejecutar_tests([rutaProyecto], [carpetaTests], [modulo], [clases], [todos], [casos], [parcial], [paginaResultados])`: sin argumentos cierra la tanda de esta sesión (con las puertas descritas arriba): espera a las pruebas en segundo plano hasta 60 s por llamada y, si no han terminado, devuelve `verificando: true` con `progreso`; `casos` fuerza la reejecución de esos ids de la sesión (solo si el usuario lo pide; tras `reemplazarCasos` se vuelven a probar solos). Nunca elicita por fallos de aserción en el modo por defecto: los que queden sin veredicto van en `decisiones` (`revisar_ia`) y `accionRequerida`. Devuelve `resumenSesion`, `respuestaFinal` (solo cuando no queda nada pendiente; si falta, `accionRequerida` va como primera clave y hay que seguirla y volver a llamar), `resultados[].deEstaSesion`, `resultadosDeOtrasSesiones`, `montajeReintentables`, `asercionesPendientes` y `revisionIaPendiente`. Solo con `verificacion: preguntar`: si [Probar tests] [Descartar] quedó sin responder, vuelve a preguntarlo antes de ejecutar; sin respuesta (o con Descartar) devuelve `{ejecutado:false, verificacion, resumenSesion, respuestaFinal}` sin ejecutar. `paginaResultados=1` recupera los resultados si la llamada terminó con error de tiempo. Con `arreglos: alFinal` (por defecto) y casos apuntados, abre la fase de arreglo: devuelve un caso por respuesta (error, pista, spec y código) para corregirlo con `arreglar_caso`.
- `estado_verificacion([rutaProyecto], [pagina])`: consulta ligera de las pruebas en segundo plano (no ejecuta nada): `progreso {serviciosConTests, verificados, verificando, enCola}`, `corregir [{servicio, intento, reemplazarCasos}]`, en el modo por defecto `revisar [{servicio, casos}]` (fallos de aserción sin veredicto) y `reemplazar [{servicio, reemplazarCasos}]` (con `error_ia` ya registrado), `errores` por caso (paginados con `pagina`; los de aserción con `tipo` revisar|reemplazar, `metodo`, `metodoTest`, `mensaje`, `motivoIa`/`intento` si existen y su `spec` sin los campos internos), `apartados.fallosAsercion` e `instruccion`. Se usa tras la nota «Verificación en segundo plano…» o un rechazo de la puerta de montaje o de revisión. Reconcilia con `spec.json` la pista en memoria de revisiones pendientes. Con `arreglos: alFinal` (por defecto) solo devuelve `progreso`, `arreglos` {pendientes, arreglados, posiblesBugs, apartados} e `instruccion`: los fallos se arreglan al final con `arreglar_caso`.
- `resolver_fallo([carpetaSalida], caso, decision, [confirmadoPorUsuario])`: decisión de un caso, solo si tú indicas expresamente qué hacer con él o con `fallosAsercion: preguntar` (tras *Revisar uno a uno en el chat* o cuando no hubo elicitation), solo tras tu respuesta explícita; `ajustar` y `eliminar` exigen `confirmadoPorUsuario=true`. Devuelve `respuestaFinal` solo si no queda nada pendiente.
- `registrar_analisis_ia(analisis, [carpetaSalida])`: paso normal para todo fallo de aserción, sin preguntarte: veredictos en lote (`{caso, veredicto: error_ia|bug_servicio|dudoso, motivo}`, máximo 100, solo casos en `fallo_asercion`; motivo basado en el código); `error_ia` autoriza un único reemplazo y después la IA llama a `generar_tests` con `reemplazarCasos`. Devuelve `respuestaFinal` solo si no queda nada pendiente; si no, `accionRequerida` «NO HAS TERMINADO: …».
- `cambiar_ruta_salida([ruta], [carpetaSalida], [guardarRutaPorDefecto])`: `/generartest ruta`; cambia la carpeta de salida de la sesión o la ruta por defecto (no escanea): [Mantener la ruta por defecto: …] [Otra ruta…].

## Perfil del proyecto y recetas aprendidas

junit-gen guarda en la carpeta de salida (nunca en el proyecto analizado) `perfil-proyecto.json`, escrito solo por junit-gen y solo ahí:

- **Qué guarda**: el montaje de cada servicio (constructor, campos o setters, dependencias y cómo se obtienen, constructor con lógica, `@PostConstruct`, `@Value` —solo si tiene valor por defecto, nunca el valor—, limpieza de `tearDown`), cómo crear un objeto de prueba de cada DTO o entidad (record, builder, setters, constructor o enum), los stubs típicos del proyecto (las llamadas a dependencias más usadas con su retorno y una expresión sugerida, marcando las de «usuario autenticado»), una huella SHA-256 por archivo fuente y las recetas aprendidas. No guarda cuerpos de métodos ni del constructor.
- **Cuándo se crea y actualiza**: en `escanear_proyecto` si la carpeta de salida ya existe (lo anuncia con una nota: «Perfil del proyecto creado en …», «actualizado (K entradas recalculadas)» o «… regenerado»), en el primer `generar_tests`, en las verificaciones y con `scan --salida`/`generate` en la terminal. Solo se recalculan las entradas cuyos archivos fuente cambiaron de huella; sin cambios no se reescribe. Si el perfil es de otro proyecto (otra ruta; mover el proyecto cuenta) o está corrupto, se regenera entero; si cambia el build de junit-gen o el esquema, se recalculan las entradas y se conservan las recetas. `detalle_servicio`, `seleccionar_funciones` y `validar_spec` no escriben.
- **Se puede borrar** en cualquier momento sin perder tests ni el `spec.json`: se vuelve a crear (sin las recetas aprendidas).
- **Recetas aprendidas**: cuando un caso con error de montaje o compilación pasa a montar tras corregir el spec, junit-gen guarda la solución: el `construccion` corregido del servicio, y de él las recetas por tipo de dependencia (valor real en `construccion.argumentos` o mock profundo) para otros servicios con una dependencia del mismo tipo; si lo que cambió fueron los stubs del caso, una pista para ese método. En generaciones posteriores se aplican solas: un spec sin `construccion` para ese servicio se genera con el montaje aprendido (aviso `RECETA_APLICADA`) y no remonta el archivo existente. Lo explícito del spec gana siempre; las recetas pasan la misma validación que el spec y, si lo hicieran inválido, se descartan (`RECETAS_NO_APLICADAS`). No se aplican a un servicio con correcciones de montaje pendientes. `detalle_servicio` las muestra en `recetas`.
- **Cómo se retiran**: una receta de tipo que hace fallar el montaje de otro servicio se excluye para ese servicio al primer fallo y se desactiva al segundo (si falla más de lo que acierta). Se guardan como máximo 200 recetas.

## Modelo de Copilot

junit-gen no elige el modelo: lo elige el selector de Copilot. Con **Auto**, Copilot puede elegir un modelo ligero que no completa el flujo (por ejemplo, salta `generar_tests` e informa de tests que no ha generado). Elige en el selector un modelo concreto y capaz. junit-gen se protege igualmente: `ejecutar_tests` rechaza lo que no se haya generado en la sesión y todas las cifras salen de `resumenSesion`.

## Tandas grandes (50 servicios o más)

- **Sin paradas**: mientras queda trabajo, toda respuesta de junit-gen empieza por `accionRequerida: NO HAS TERMINADO (servicio k de N): llama ahora a …` y el agente no escribe nada hasta `respuestaFinal`.
- **Límite de peticiones por turno (VS Code)**: el ajuste `chat.agent.maxRequests` limita cuántas peticiones encadena Copilot en modo Agent antes de pausar y pedir que pulses continuar. Una tanda necesita unas 2–3 llamadas por servicio (100–150 para 50 servicios). Antes de una tanda grande súbelo en *Ajustes* (busca `chat.agent.maxRequests`; p. ej. 500) o pulsa **Continuar** cada vez que aparezca. junit-gen no cambia la configuración de VS Code. El nombre y el valor por defecto dependen de la versión de VS Code/Copilot y del runtime: compruébalo en tu instalación.
- **Contexto**: Copilot resume la conversación cuando se llena. El estado real está en `tanda.json`: si el agente pierde el hilo llama solo a `escanear_proyecto {continuar: true}`; si el turno se corta, escribe `/generartest continuar` (mismo chat o uno nuevo).
- **Servicio atascado**: tras 2 llamadas seguidas a `generar_tests` sin casos nuevos (o 4 specs rechazados seguidos) junit-gen cierra ese servicio, lo lista en «Servicios incompletos» de la respuesta final y pasa al siguiente.
- **Medición**: `tanda.json` guarda en `mediciones` el tiempo y el número de llamadas por servicio (con contador por herramienta, rechazadas, bytes y llamadas de la fase de arreglo) y, al cerrar la tanda, `informe.md` añade la sección «Medición de la tanda» con una fila por servicio, el total y la media por servicio, y la tabla «Fase de arreglo» (por servicio: tiempo en arreglo —`msArreglo` en `tanda.json`—, llamadas a `arreglar_caso`, `registrar_analisis_ia`, `ejecutar_tests` y `detalle_servicio`, casos, arreglados, automáticos y menos estrictos) con el tiempo de generación y el de la fase de arreglo.
- **Arreglos al final**: durante la tanda no se corrige nada; los casos que fallan se apuntan en `tanda.json` y, con todos los servicios generados, junit-gen corrige solo lo mecánico y da el resto agrupado por servicio (error, pistas, spec y código) para corregirlos en una llamada a `arreglar_caso`, que recompila y ejecuta una vez esa clase de test. `respuestaFinal` puede traer «Arreglados automáticamente (sin IA)» y «Tests menos estrictos (revisar en el informe)». Máximo 3 intentos por caso; lo irreparable se aparta (comentado, nunca borrado).

## Respuestas compactas

Ninguna respuesta de herramienta supera **7 000 bytes** UTF-8 (aviso + notas + cuerpo), para que Copilot no la vuelque a un archivo temporal (Copilot vuelca a archivo las respuestas de más de ~8 KB: no subas el límite cerca de ese valor). Lo que no cabe se pide por partes y el recorte siempre se declara:

- `escanear_proyecto`: resumen breve; las secciones largas con `seccion` + `pagina`.
- `detalle_servicio`: tramos de varios métodos con `paginacion.siguiente` (argumentos exactos de la próxima llamada), cuerpos compactados (sin comentarios, logs, líneas en blanco ni sangría larga), sin repetir la cabecera en las páginas siguientes (la página 1 puede traer solo la cabecera y los métodos siguen con `paginacion.siguiente`), cuerpos largos con `parteCuerpo` (partidos por tamaño ya escapado en JSON, siempre en las mismas partes), tipos auxiliares completos con `tipos`; aprovecha hasta 6 600 bytes de cuerpo cuando el aviso y las notas lo permiten.
- Paquete de trabajo (`seleccionar_funciones` con elección o `detalle_servicio` con `paquete`): unos 13 000 bytes en total repartidos en como máximo 2 respuestas (la confirmación y la página 1 comparten presupuesto; la página 2 solo si `paginacion.siguiente` no es null). Prioridad: funciones elegidas > métodos de la clase que llaman (profundidad 1 antes que 2-3) > stubs > tipos > helpers externos; lo que no cabe va a `omitido`. Configurable: `JUNITGEN_MAX_RESPUESTA` (o `-Djunitgen.maxRespuesta`; límite por respuesta, por defecto 7 000, rango 7 000–32 000) y `JUNITGEN_PRESUPUESTO_PAQUETE` (o `-Djunitgen.presupuestoPaquete`; total del paquete sumando sus páginas, por defecto 13 000, rango 6 000–32 000); un valor no numérico o fuera de rango usa el valor por defecto. Con un límite por respuesta de ~14 000 o más, el paquete va en una sola respuesta; ten en cuenta que Copilot vuelca a archivo las respuestas de más de ~8 KB.
- `seleccionar_funciones` con elección: `generar_tests` solo añade `siguienteServicio` si las candidatas caben enteras.
- `ejecutar_tests`: `paginaResultados` para las páginas siguientes de la última ejecución; mientras las pruebas en segundo plano no terminan, una respuesta corta `verificando: true` con `progreso` (sin repetir resultados).
- `estado_verificacion`: solo progreso, casos a corregir o revisar y errores recortados (con el `spec` de cada fallo de aserción; paginados con `pagina`).
- Nota de cabecera «Verificación en segundo plano…» (solo con `arreglos: inmediato`; ≤ 300 bytes) en cada respuesta mientras queden correcciones de montaje o fallos de aserción por revisar (montaje primero); cuenta en el presupuesto de `detalle_servicio`.
- Fase de arreglo: cada respuesta trae los casos pendientes de un servicio que quepan (máximo 6; spec sin textos, error, pista, pistas y `codigo` construido con el mismo paquete de trabajo, limitado a los métodos de los casos: los métodos, los métodos de la clase que llaman, tipos, `mocks` y helpers externos, sin stubs típicos y acotado al presupuesto, que aprovecha `JUNITGEN_MAX_RESPUESTA`; no hace falta `detalle_servicio` salvo que venga `codigo.masCodigo`, que indica la llamada); durante la generación solo el contador «N fallos apuntados para arreglar al final» en la primera línea y `plan.porArreglarAlFinal`.
- Cabecera de acción: primera línea `accionRequerida: …` (≤ 480 bytes, dentro del límite de cabecera); `generar_tests` durante la tanda devuelve solo ids generados, `plan` compacto y `siguienteServicio`.
- `validar_spec`, `generar_tests` y el resto: `totales`, `recortes` («mostrados X de Y») y `comoVerElResto`.

Si aun así una respuesta lo superara, una red de seguridad la recorta, lo indica con `[junit-gen: respuesta recortada a 7 000 bytes]` (con el límite configurado si se cambió) y lo registra en stderr.

## Uso por terminal

```
junit-gen scan     --proyecto C:\ruta\proyecto --salida C:\ruta\salida
junit-gen functions --proyecto C:\ruta\proyecto --clase com.ejemplo.service.UsuarioServiceImpl [--salida C:\ruta\salida]
junit-gen detail   --proyecto C:\ruta\proyecto --clase com.ejemplo.service.UsuarioServiceImpl [--todas]
junit-gen validate --proyecto C:\ruta\proyecto --spec spec-nuevo.json --salida C:\ruta\salida [--todas]
junit-gen generate --spec spec-nuevo.json --salida C:\ruta\salida [--no-probar] [--todas]
junit-gen verify   --proyecto C:\ruta\proyecto --salida C:\ruta\salida
junit-gen resolve  --salida C:\ruta\salida --caso contar-activos --decision bug
junit-gen resolve  --salida C:\ruta\salida --caso contar-activos --decision ajustar --confirmar
junit-gen prompt   (prompt para usar cualquier IA sin MCP)
junit-gen schema   (esquema JSON del spec)
```

- `scan`: informe del proyecto. Con `--salida` crea `escaneo.md`, `escaneo.json` y `perfil-proyecto.json` (o lo actualiza si cambió algún fuente). Opciones: `--modulo`, `--json`, `--filtro`, `--sin-cubrir`, `--pagina`, `--tam`.
- `functions`: «Funciones candidatas» del servicio (sin cuerpos) con el cupo, para que la IA elija; la elección va en `servicios[].seleccion` del spec (`--salida` muestra la selección `ia` ya guardada).
- `detail`: detalle JSON de un servicio con sus funciones seleccionadas y `maxCasos`, `llama` por método, `stubsTipicos` y `modo`/`crear`/`setters` de cada tipo auxiliar (`--metodos a,b` para limitarlo; `--todas` para el 100 % de las funciones).
- `--todas` (en `detail`, `validate` y `generate`): el 100 % de las funciones públicas, incluidas las triviales; úsalo igual en los tres.
- `validate`: errores, avisos y duplicados; código 1 si el spec no es válido. Opcional: `generate` valida igual.
- `generate`: valida el spec, aplica las recetas de `perfil-proyecto.json` (lo crea en el primer `generate` si no existe) y genera o amplía los tests. `--reemplazar id1,id2` reescribe casos ya generados; `--eliminar id --confirmar` los quita. Por defecto prueba solo los tests generados en esa llamada, servicio a servicio (en `<salida>\.junit-gen-tmp`, sin tocar el proyecto; el classpath y las clases del proyecto se preparan una vez por módulo y se reutilizan; código 1 si alguno no pasa); `--no-probar` lo evita. La clave `verificacion` de `junit-gen.yml` cambia el valor por defecto (`nunca`: no prueba; `preguntar`: pregunta `¿Probar tests? [p]robar / [d]escartar` si hay consola); `--probar`/`--no-probar` prevalecen.
- `verify`: compila y ejecuta los tests generados (`--modulo`, `--clase`); código 1 si alguno no pasa.
- `resolve`: `bug` (mantener y marcar como posible bug), `ajustar` (requiere `--confirmar`: autoriza un único reemplazo e indica cómo corregir el esperado) o `eliminar` (requiere `--confirmar`). `generate --reemplazar/--eliminar` rechaza, sin escribir nada, los casos en `fallo_asercion`, `posible_bug` o `dudoso` sin esa decisión.

Códigos de salida: **0** correcto, **1** errores de validación o tests fallidos, **2** uso incorrecto, **3** rechazo de seguridad.

Sin MCP: ejecuta `scan`, pega `escaneo.md` y la salida de `functions` a la IA junto con `prompts\prompt-sin-mcp.md`; la IA elige las funciones; ejecuta `detail --metodos a,b` con las elegidas, guarda el JSON que devuelve (con `seleccion` en cada servicio) y sigue con `generate` (`validate` es opcional), `verify` y `resolve`. Sin `seleccion`, se usa la guardada (`ia`) en `spec.json` o la automática.

## Formato del spec

El esquema completo está en el JAR (`junit-gen schema`) y es la misma fuente que usan las herramientas MCP y los prompts. Ejemplo:

```json
{
  "version": 1,
  "proyecto": {"ruta": "C:/proyectos/tienda", "modulo": null},
  "estilo": {"patronNombre": "{metodo}_{escenario}", "displayName": false, "comentariosGwt": false,
             "aserciones": "junit", "visibilidadClase": "package", "nombreCampoSut": "service"},
  "servicios": [{
    "clase": "com.ejemplo.tienda.pedidos.PedidoServiceImpl",
    "claseTest": null,
    "seleccion": {"origen": "ia", "funciones": [{"nombre": "crear", "motivo": "alta de pedidos con validación y reserva de stock"}],
                  "omitidas": [{"nombre": "buscar", "motivo": "consulta simple sin lógica de negocio"}]},
    "imports": ["com.ejemplo.tienda.error.SinStockException"],
    "fixtures": [{"nombre": "pedido", "tipo": "Pedido",
                  "parametros": [{"tipo": "Long", "nombre": "id"}],
                  "pasos": ["Pedido p = new Pedido()", "p.setId(id)"], "devuelve": "p"}],
    "casos": [{
      "id": "crear-sin-stock",
      "metodo": "crear", "parametros": ["PedidoRequest"],
      "escenario": "lanzaSinStockSiNoHayUnidades",
      "descripcion": "Rechaza el pedido si no hay stock suficiente del producto",
      "queHaceOriginal": "Crea un pedido nuevo reservando el stock del producto pedido",
      "queComprueba": "Que se lanza SinStockException y no se guarda el pedido si no hay stock",
      "datos": [{"tipo": "PedidoRequest", "nombre": "request", "valor": "new PedidoRequest(42L, 3)"}],
      "preparacion": [],
      "stubs": [{"mock": "stockClient", "llamada": "disponible(42L)", "devuelve": "0"}],
      "estaticos": [], "construcciones": [],
      "argumentos": ["request"],
      "esperado": {"tipo": "excepcion", "excepcion": "SinStockException", "mensaje": "Producto 42 sin stock", "mensajeContiene": null},
      "aserciones": [],
      "verificaciones": [{"mock": "pedidoRepository", "llamada": "save(any())", "veces": 0}]
    }]
  }]
}
```

- Módulos: junit-gen busca la clase de cada servicio en **todos** los módulos, así que un mismo spec puede mezclar servicios de módulos distintos (cada test va a la carpeta de su módulo). `proyecto.modulo` es opcional y solo una pista para desambiguar; si no existe, se ignora con aviso `MODULO_DESCONOCIDO`, y si la clase está en otro módulo se usa ese con aviso `MODULO_CORREGIDO` (nunca error). `servicios[].modulo` (opcional, ruta relativa o nombre) solo hace falta si el mismo FQN existe en varios módulos (error `CLASE_AMBIGUA` con los candidatos `modulo:fqn`).
- Las expresiones son código Java en cadenas (`"50L"`, `"\"texto\""`); junit-gen las parsea con JavaParser y las reimprime, así que no se puede inyectar código.
- `esperado.tipo`: `valor`, `excepcion`, `nulo`, `noNulo`, `verdadero`, `falso`, `sinRetorno` o `aserciones` (`aserciones[].tipo`: `igual`, `noIgual`, `nulo`, `noNulo`, `verdadero`, `falso`, `mismo`, `tamano`, `vacio`, `contiene`; el valor devuelto está en la variable `resultado`).
- Arrays: con `esperado.tipo=valor` (o una aserción `igual`) que compara arrays —el método devuelve un array, el esperado es `new T[]{…}`, un cast a array o un dato de tipo array— la rama JUnit genera `assertArrayEquals` (primitivos y objetos; en JUnit 4 con delta `0.0` para `double[]`/`float[]` y `assertTrue(Arrays.equals(…))` para `boolean[]`; si el retorno se declara `Object`, se hace cast al tipo array). Con AssertJ, `isEqualTo` ya compara arrays por contenido.
- `preparacion`: una sentencia Java por elemento. Si un elemento trae varias sentencias en una cadena y el conjunto es válido, junit-gen lo separa en sentencias sueltas (aviso `PREPARACION_SEPARADA`); si no, error `SENTENCIA_INVALIDA` como siempre.
- Stubs: uno de `devuelve`, `devuelveSecuencia`, `lanza` o `responde`; `forma` `auto|when|do`. `mock` es el nombre de la dependencia en `detalle_servicio`.
- `estaticos` (mockStatic) y `construcciones` (mockConstruction) solo con Mockito ≥ 5 (o ≥ 3.4/3.5 con mockito-inline).
- Dependencias de valor del constructor (String, primitivos, `@Value`): su literal va en `construccion.argumentos`.
- `construccion` (todo opcional): `argumentos` acepta además una instancia real para una dependencia mock (p. ej. `"objectMapper": "new ObjectMapper()"`) o un valor para cualquier campo de instancia (se fija por reflexión tras construir); `stubs` (máx. 30, `lenient`) se ejecutan en `setUp` antes de construir el servicio; `profundos` declara mocks con `RETURNS_DEEP_STUBS` (admiten llamadas encadenadas en stubs y verificaciones); `postConstruct: true` invoca los `@PostConstruct` tras montar (por defecto no). No pongas `mock(X.class)` en `argumentos`: las dependencias ya son `@Mock` (y los campos derivados se inyectan solos); junit-gen ignora esa clave con aviso `ARGUMENTO_MOCK_IGNORADO` (`mock(X.class, RETURNS_DEEP_STUBS)` pasa a `profundos`). Sin Mockito en el módulo, un argumento con forma de mock es error `SIN_MOCKITO`. Para deep stubs usa `profundos`.
- En `detalle_servicio`, `profundo: true` indica un mock que ya se declara con deep stubs porque el constructor lo usa, y `CAMPO_DERIVADO` un campo calculado en el constructor que el test sustituye por un `@Mock` por reflexión. Un fallo al construir (`InjectMocksException` o NPE en `<init>`) se corrige con `construccion.*`, no en los casos. Cambiar `construccion` remonta el archivo: se reescribe solo el montaje y los casos existentes se conservan y se vuelven a probar. Para un caso con un montaje distinto, pon en `preparacion` el stub y `service = new …(…)`.
- `valorPorDefecto` en `detalle_servicio`: el defecto de `@Value("${clave:defecto}")` (literal o constante a un salto, convertido según el tipo; sin SpEL ni placeholders anidados) se usa al construir o se inyecta en el campo; `construccion.argumentos.<nombre>` lo cambia.
- `ELEMENTO_COLECCION`: para `List|Collection|Set|Iterable<X>`, `Map<String,X>`, `Optional<X>` y `ObjectProvider|Provider|Supplier<X>` de beans se declara un único `@Mock` del elemento; la colección se pasa como `List.of(mock)` (Java < 9: `Collections.singleton*`) y el proveedor lo devuelve con stubs `lenient`; `argumentos.<contenedor>` lo sustituye. También se montan los campos y setters anotados heredados (hasta 5 niveles).
- Recetas por tipo: mocks profundos para `WebClient`, `RestClient`, `RedisTemplate`, `EntityManager`, `MongoTemplate`, `JdbcClient`, `DSLContext`…; instancias reales por defecto para `Clock` (fijo 2024-01-15T10:00:00Z UTC), `MeterRegistry`, `TransactionOperations`, `TransactionTemplate`, `Executor`/`TaskExecutor` y tipos valor del JDK: `Instant`, `LocalDate`, `LocalDateTime`, `LocalTime`, `OffsetDateTime`, `ZonedDateTime` (2024-01-15 10:00 UTC), `ZoneId` (`ZoneOffset.UTC`), `UUID` (fijo), `Locale` (`Locale.ROOT`) y `Charset` (`StandardCharsets.UTF_8`) (no admiten stubs; `construccion.argumentos` manda). Las dependencias cuyo tipo es una clase `@ConfigurationProperties` con constructor sin argumentos siguen siendo mock, con una nota visible que indica cómo usar una instancia real (`construccion.argumentos.<nombre> = new <Tipo>()`). Los tipos miembro (anidados) del servicio, propios o heredados no privados de sus superclases e interfaces del proyecto, se resuelven solos: import `paquete.Externa.Interna`, tipo de retorno tipado, enum anidado como valor y presentes en `tiposAuxiliares` y `tipos`. Los records no abstractos se pueden generar (montaje explícito con el constructor canónico). Los servicios que usan `SecurityContextHolder`/`RequestContextHolder` limpian el contexto en `tearDown`.
- `pista` en los resultados con `error_montaje`: indicación corta según la excepción (constructor, stubs no invocados, dependencia real, tipo de devuelve).
- `queHaceOriginal` y `queComprueba`: obligatorios en casos nuevos (1 frase, máx. 160 caracteres; si se pasa, se recorta con aviso `COMENTARIO_RECORTADO`). junit-gen los sanea (sin saltos de línea, barras invertidas, `//` ni marcadores) y los escribe sobre el método de test como `// Función: …` y `// Test: …` (máx. 2 líneas cada uno). No forman parte de la huella ni del hash de contenido.
- `seleccion` (solo sin MCP; con MCP se ignora y se usa `seleccionar_funciones`): `{origen: ia|usuario, funciones: [{nombre, motivo}], omitidas: [{nombre, motivo}]}`; se valida con las mismas reglas que `seleccionar_funciones` salvo que sea idéntica a la ya guardada en `spec.json`. junit-gen escribe en `spec.json` la selección usada (también `java` o `todas`), pero solo recarga la de origen `ia`.
- Solo funciones seleccionadas y como máximo `maxCasos` casos por función (contando los ya generados en la salida); los casos existentes (id ya en `spec.json`) siguen siendo válidos.
- En `spec.json` la herramienta añade por caso `huella`, `huellaEntrada`, `estado`, `intentosMontaje`, `veredictoIa` (último veredicto de la revisión de la IA), `motivoIa` (motivo de ese último veredicto) y `autorizacionCambio` (`ajustar` o `error_ia`: permite un único reemplazo de un caso protegido y se consume al reemplazarlo). Los valores de estos campos en el spec de entrada se ignoran.

## junit-gen.yml (opcional)

En la raíz del proyecto y/o en cada módulo (el del módulo prevalece). Solo se lee.

```yaml
incluir:
  clases: ["com.ejemplo.util.CalculadoraPrecio"]   # fuerza la inclusión
  paquetes: ["com.ejemplo.legacy.**"]
excluir:
  clases: ["*Mapper", "com.ejemplo.service.ViejoService"]
  paquetes: ["com.ejemplo.generated.**"]
aserciones: auto            # assertj | junit | auto
jdk: C:\Program Files\Java\jdk-11
maven:
  home: C:\herramientas\apache-maven-3.9.9
  settings: C:\Users\yo\.m2\settings-empresa.xml
classpath:                  # evita Maven/Gradle si se indica
  - C:\libs\dependencia-1.jar
umbralPuntuacion: 40
porcentajeFunciones: 60     # cupo: % de funciones no triviales que la IA puede elegir (1..100; por defecto 60)
verificacion: siempre      # siempre | preguntar | nunca (por defecto siempre)
orden: alfabetico          # alfabetico | prioridad (por defecto alfabetico)
fallosAsercion: revisarIa   # revisarIa | preguntar (por defecto revisarIa)
arreglos: alFinal   # alFinal | inmediato (por defecto alFinal)
```

Un `porcentajeFunciones` no válido se ignora con un aviso y se usa 60.

`verificacion` decide si los tests generados se prueban: `siempre` (por defecto) los compila y ejecuta sin preguntar al terminar de generar; `preguntar` pregunta una vez por sesión [Probar tests] [Descartar]; `nunca` no los ejecuta salvo petición expresa. La prueba se hace siempre en `<salida>\.junit-gen-tmp`, sin modificar el proyecto. Solo se lee del `junit-gen.yml` de la raíz (en los de módulo se ignora). Un valor no válido se ignora con un aviso y se usa `siempre`.

`orden` decide qué N servicios entran en el plan con `/generartest N` y en qué orden se recorren: `alfabetico` (por defecto) = los N primeros por nombre simple de la clase, sin distinguir mayúsculas; `prioridad` = los de más métodos sin tests y más ramas primero. Solo se lee del `junit-gen.yml` de la raíz. Un valor no válido se ignora con un aviso y se usa `alfabetico`.

`fallosAsercion` decide qué pasa con los tests que fallan porque el resultado no es el esperado: `revisarIa` (por defecto) = la IA los revisa sola con el código del servicio, sin preguntarte, y registra un veredicto por caso con `registrar_analisis_ia` (`error_ia` → corrige el test y se vuelve a probar, máximo 3 intentos; `bug_servicio` → queda como posible bug en informe.md; `dudoso` si no puede decidir); `preguntar` = al cerrar la tanda junit-gen hace una única pregunta [Es un bug, mantener los tests] [Ajustar lo esperado] [Eliminar casos] [Revisar con IA] [Revisar uno a uno en el chat]. Solo se lee del `junit-gen.yml` de la raíz. Un valor no válido se ignora con un aviso y se usa `revisarIa`.

`arreglos` decide cuándo se arreglan los casos que fallan durante una tanda: `alFinal` (por defecto) = durante la generación no se corrige nada (se apuntan en `tanda.json`) y, con todos los servicios generados, la fase de arreglo corrige solo lo mecánico y da el resto agrupado por servicio para corregirlos en una llamada a `arreglar_caso` (máximo 3 intentos; lo irreparable se aparta); `inmediato` = como antes: la nota «Verificación en segundo plano…» y la puerta obligan a corregir cada servicio antes de empezar otro. Solo aplica con plan, `verificacion` distinta de `preguntar` y `fallosAsercion: revisarIa`; un valor no válido se ignora con un aviso y se usa `alFinal`.

Patrones: `*` no cruza puntos y `**` cruza cualquier cosa. Las clases se comparan por FQN y por nombre simple.

## Estructura de la carpeta de salida

```
<salida>\
  spec.json                 spec acumulado (revisable y regenerable sin IA)
  informe.md                informe en lenguaje llano, un apartado por ejecución (lo más reciente al final): resumen, tests creados con lo que comprueba cada uno, funciones probadas y por qué (solo cuando la selección es nueva o cambia), resultado de cada test y, cuando se rehacen tests, por qué y qué cambió en frases («Se añadió…», «Se quitó…», «Se cambió… de X a Y»; valores truncados a 120 caracteres, máximo 15 líneas por test); si algún caso falló durante la tanda, la sección «Arreglos al final» (por caso: qué fallaba, qué se cambió, intentos y resultado)
  tanda.json                estado de la tanda MCP (plan, índice, estado por servicio, `renunciados` por método y `cierre` de los servicios cerrados por atasco) con `fase`, `arreglos` (casos que fallaron, para arreglarlos al final, con `autoIntentos`, `autoCambios`, `automatico`, `origen`, `menosEstricto` y `avisosCalidad`) y `mediciones` (con `msArreglo` por servicio) para retomarla con `continuar`
  perfil-proyecto.json      perfil del proyecto: montaje de servicios, cómo crear los tipos de datos, stubs típicos, huellas de los fuentes y recetas aprendidas (se puede borrar sin perder tests)
  escaneo.md, escaneo.json  solo con scan --salida
  <móduloRel>\<Servicio>\<Clase>Test.java   (sin <móduloRel> si la raíz es el módulo; <Servicio> = clase sin «Impl»; <Servicio>__<paquete> si dos servicios del módulo coinciden)
  .junit-gen-tmp\           temporales de verificación (único sitio donde se borra)
    <módulo>\prep\          caché por módulo: classpath (Maven/Gradle) y clases del proyecto, con prep.json; se reutiliza mientras no cambie su huella (poms o build.gradle, junit-gen.yml, JDK y clases o fuentes main)
    <módulo>\lote\          ejecución síncrona de ejecutar_tests (todos, clases o modulo) y verify
    <módulo>\srv\<Servicio>-<hash>\   prueba de un servicio (segundo plano o generate): solo su archivo de test y sus clases
```

Los archivos generados por versiones anteriores en `<móduloRel>\src\test\java\<paquete>\` no se mueven: se siguen encontrando, ampliando y verificando ahí.

Si ya existe un test con el mismo nombre en el proyecto, el generado se llama `<Clase>GeneradoTest` para que no lo sobrescribas al copiarlo. Los comentarios `// junit-gen:` son marcadores que permiten ampliar el archivo sin reescribir lo existente; si editas el archivo a mano y los quitas, junit-gen ya no lo toca.

## Cómo copiar los tests al proyecto

Es siempre un paso **manual**: revisa los tests en la carpeta de salida y copia los que te convenzan a `src\test\java` del módulo correspondiente, manteniendo el paquete. La carpeta de salida ya no replica el paquete (una carpeta por servicio); el destino exacto de cada test está en su cabecera (`// Copiar a:`) y en la tabla «Dónde copiar cada test» de `informe.md`. junit-gen y el agente nunca lo hacen por ti.

## Limitaciones

- La cobertura con **JaCoCo** (descartar casos que no cubren ramas nuevas) no está incluida en esta versión.
- **Gradle** con `buildSrc` o `includeBuild`: no se obtiene el classpath automáticamente (compilaría código del build); indica `classpath` en `junit-gen.yml`.
- Dependencias Maven con `scope=system` relativas al proyecto y `.mvn/maven.config`: el pom espejo no las reproduce; usa `classpath` o `maven.settings` en `junit-gen.yml`.
- **Los tests ejecutan código real del proyecto**: si ese código escribe en rutas absolutas, podría modificar archivos. Se mitiga ejecutando con el directorio de trabajo y `java.io.tmpdir` en el temporal y con la alerta de integridad, pero revisa qué hace el código antes de probar. Con `verificacion: siempre` (por defecto) se ejecutan sin preguntar: usa `preguntar` o `nunca` si no quieres ejecución automática.
- Las pruebas van en segundo plano y el cierre con `ejecutar_tests` espera como máximo 60 s por llamada (devuelve `verificando: true` si no han terminado), así que no debería superarse el límite de ~180 s del cliente. La primera prueba de cada módulo (resolución del classpath con descarga de dependencias) es la más lenta; las siguientes reutilizan la preparación cacheada. Con `todos`, `clases` o `modulo` la ejecución es síncrona: si termina con `MCP error -32001`, junit-gen la completa igualmente y `ejecutar_tests {paginaResultados: 1}` devuelve sus resultados sin repetirla. Indicar `classpath` en `junit-gen.yml` reduce ese tiempo.
- La detección de tipos usa un índice propio (sin resolución simbólica completa): en casos raros puede pedir que añadas un import a `servicios[].imports`. Los tipos que aparecen como argumentos genéricos (`List<X>`, `Map<K, V>`, `Optional<List<X>>`) también se importan. Si aun así javac no encuentra una clase que junit-gen sabe resolver, añade el import al test generado y recompila una vez sin gastar intentos; queda anotado en los avisos y en el informe.
