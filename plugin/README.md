# junit-gen (plugin de Claude Code)

Genera, compila y ejecuta tests unitarios JUnit 5 + Mockito de los servicios de tu proyecto Java sin tocar el proyecto: todo se escribe en una carpeta de salida aparte. La IA solo propone los casos en un spec JSON; junit-gen escribe los `.java`, los prueba y te deja un informe.

## Instalación

```
/plugin marketplace add Ftejero21/junit-gen
/plugin install junit-gen@junit-gen
```

## Uso

Abre Claude Code en la carpeta de tu proyecto Java (Maven o Gradle) y escribe, por ejemplo:

```
/junit-gen:generartest genérame los tests de 2 servicios
```

El corto `/generartest` funciona mientras ningún otro comando use ese nombre.

## Modo paralelo

Con muchos servicios, el plugin reparte la tanda en bloques y lanza un subagente por bloque (la skill pasa `paralelo: true` a `escanear_proyecto`): el agente principal solo escanea, lanza los subagentes y cierra con `ejecutar_tests {}`; cada subagente trabaja su bloque con `tomar_bloque`, `generar_tests` (con la selección de funciones dentro del spec), `ejecutar_tests {bloque}` y `arreglar_caso`. Con menos de 3 servicios, un solo bloque que trabaja el propio agente principal. Variables de entorno: `JUNITGEN_SERVICIOS_BLOQUE` (4 por defecto) y `JUNITGEN_MAX_BLOQUES` (6 por defecto), entre 1 y 10; el plugin fija `JUNITGEN_MAX_RESPUESTA=24000` en su `.mcp.json`. La skill permite `Agent`; los subagentes pueden pedir permiso para las herramientas MCP de junit-gen: para no aprobarlas una a una, añade `mcp__plugin_junit-gen_junit-gen__*` y `Agent` a `permissions.allow` de tu `settings.json`. Detalles en [docs/MANUAL.md](../docs/MANUAL.md#modo-paralelo-subagentes).

## Velocidad

La IA escribe un spec compacto (sin `version`, `proyecto`, `estilo`, `id`, `claseTest`, `imports` ni `parametros` salvo sobrecargas; `queHaceOriginal` una vez por función y `queComprueba` en cada caso) y junit-gen lo completa. `escanear_proyecto` reutiliza el escaneo en memoria solo si la huella de las fuentes y de los archivos de build no cambió. El `.mcp.json` del plugin fija `JUNITGEN_PREESCANEO=1` (escanea en segundo plano al arrancar el servidor MCP el proyecto del directorio de trabajo si tiene ruta por defecto guardada; algo de CPU al arrancar). `JUNITGEN_CARGA_DIRECTA=1` (no incluida) marca las herramientas principales como de carga directa para clientes con carga diferida.

## Requisitos

- Java 17 o superior (JDK) en el `PATH`. El plugin no incluye JDK.
- Maven o Gradle disponibles para resolver el classpath del proyecto.

Mientras dura una tanda, el plugin impide al agente editar archivos o ejecutar comandos de terminal; el bloqueo termina con tu siguiente mensaje. Documentación completa en el [README del repositorio](../README.md) y en [docs/MANUAL.md](../docs/MANUAL.md).
