#!/bin/sh
# Instala junit-gen en GitHub Copilot (VS Code) desde este repositorio.
#
# Comprueba que hay Java 17 o superior (JDK), registra el servidor MCP "junit-gen" en el mcp.json de
# VS Code y en ~/.copilot/mcp-config.json (conservando los demás servidores) y copia la skill
# /generartest a ~/.copilot/skills/generartest/. Se puede ejecutar varias veces. No descarga nada.
#
# Uso: ./instalar-copilot.sh [--vscode-mcp <archivo>] [--copilot-dir <carpeta>]

set -u

uso() {
    echo "Uso: ./instalar-copilot.sh [--vscode-mcp <archivo>] [--copilot-dir <carpeta>]"
}

REPO=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
JAR="$REPO/plugin/junit-gen.jar"
INSTALADOR="$REPO/instalador/InstalarCopilot.java"

VSCODE_MCP=""
COPILOT_DIR=""
while [ $# -gt 0 ]; do
    case "$1" in
        --vscode-mcp)
            [ $# -ge 2 ] || { echo "Falta el valor de --vscode-mcp." >&2; uso >&2; exit 1; }
            VSCODE_MCP=$2
            shift 2
            ;;
        --copilot-dir)
            [ $# -ge 2 ] || { echo "Falta el valor de --copilot-dir." >&2; uso >&2; exit 1; }
            COPILOT_DIR=$2
            shift 2
            ;;
        -h|--help)
            uso
            exit 0
            ;;
        *)
            echo "Parámetro desconocido: $1" >&2
            uso >&2
            exit 1
            ;;
    esac
done

JAVA_EXE=""
JAVA_CMD=""
if command -v java >/dev/null 2>&1; then
    JAVA_EXE=$(command -v java)
    JAVA_CMD="java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$(CDPATH= cd -- "$JAVA_HOME/bin" && pwd -P)/java"
    JAVA_CMD="$JAVA_EXE"
fi

MAYOR=""
if [ -n "$JAVA_EXE" ]; then
    VERSION=$("$JAVA_EXE" -version 2>&1 | grep 'version "' | head -n 1 | sed 's/.*version "\([^"]*\)".*/\1/')
    MAYOR=${VERSION%%[!0-9]*}
    if [ "$MAYOR" = "1" ]; then
        RESTO=${VERSION#1.}
        MAYOR=${RESTO%%[!0-9]*}
    fi
fi
if [ -z "$JAVA_EXE" ] || [ -z "$MAYOR" ] || [ "$MAYOR" -lt 17 ]; then
    [ -n "$JAVA_EXE" ] && echo "Java encontrado en $JAVA_EXE (versión ${MAYOR:-desconocida})."
    echo "junit-gen necesita Java 17 o superior (JDK) en el PATH o en JAVA_HOME. Instala un JDK 17+ y vuelve a ejecutar este script." >&2
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "No se encuentra $JAR. Descarga el repositorio completo (incluye plugin/junit-gen.jar)." >&2
    exit 1
fi

set -- -cp "$JAR" "$INSTALADOR" --repo "$REPO" --java-cmd "$JAVA_CMD"
if [ -n "$VSCODE_MCP" ]; then
    set -- "$@" --vscode-mcp "$VSCODE_MCP"
fi
if [ -n "$COPILOT_DIR" ]; then
    set -- "$@" --copilot-dir "$COPILOT_DIR"
fi

"$JAVA_EXE" "$@"
CODIGO=$?
if [ "$CODIGO" -ne 0 ]; then
    echo "" >&2
    echo "La instalación no terminó correctamente (código $CODIGO)." >&2
    echo "Pista: el instalador necesita un JDK (no solo un JRE). La configuración manual está en docs/MANUAL.md." >&2
fi
exit "$CODIGO"
