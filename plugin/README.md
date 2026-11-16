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

## Requisitos

- Java 17 o superior (JDK) en el `PATH`. El plugin no incluye JDK.
- Maven o Gradle disponibles para resolver el classpath del proyecto.

Mientras dura una tanda, el plugin impide al agente editar archivos o ejecutar comandos de terminal; el bloqueo termina con tu siguiente mensaje. Documentación completa en el [README del repositorio](../README.md) y en [docs/MANUAL.md](../docs/MANUAL.md).
