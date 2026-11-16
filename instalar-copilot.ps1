<#
.SYNOPSIS
    Instala junit-gen en GitHub Copilot (VS Code) desde este repositorio.

.DESCRIPTION
    Comprueba que hay Java 17 o superior (JDK), registra el servidor MCP "junit-gen" en el mcp.json de
    VS Code y en ~/.copilot/mcp-config.json (conservando los demas servidores) y copia la skill
    /generartest a ~/.copilot/skills/generartest/. Se puede ejecutar varias veces. No descarga nada.

.PARAMETER McpJson
    Ruta alternativa del mcp.json de VS Code (por defecto %APPDATA%\Code\User\mcp.json).

.PARAMETER CopilotDir
    Carpeta alternativa de Copilot (por defecto ~/.copilot).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\instalar-copilot.ps1
#>
param(
    [string]$McpJson = "",
    [string]$CopilotDir = ""
)

$ErrorActionPreference = 'Continue'

function Get-JavaMajor([string]$exe) {
    $texto = ""
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $exe
        $psi.Arguments = '-version'
        $psi.UseShellExecute = $false
        $psi.RedirectStandardError = $true
        $psi.RedirectStandardOutput = $true
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $texto = $p.StandardError.ReadToEnd() + $p.StandardOutput.ReadToEnd()
        $p.WaitForExit()
    } catch {
        return 0
    }
    if ($texto -match 'version "(\d+)(\.(\d+))?') {
        $mayor = [int]$Matches[1]
        if ($mayor -eq 1 -and $Matches[3]) {
            $mayor = [int]$Matches[3]
        }
        return $mayor
    }
    return 0
}

function Limpiar-Ruta([string]$ruta) {
    if ($ruta.Length -gt 3) {
        return $ruta.TrimEnd('\', '/')
    }
    return $ruta
}

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $repo 'plugin\junit-gen.jar'
$instalador = Join-Path $repo 'instalador\InstalarCopilot.java'

$javaExe = $null
$javaCmd = $null
$enPath = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($enPath) {
    $javaExe = $enPath.Path
    $javaCmd = 'java'
} elseif ($env:JAVA_HOME) {
    $candidato = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $candidato -PathType Leaf) {
        $javaExe = (Resolve-Path -LiteralPath $candidato).Path
        $javaCmd = $javaExe
    }
}

$mayor = 0
if ($javaExe) {
    $mayor = Get-JavaMajor $javaExe
}
if (-not $javaExe -or $mayor -lt 17) {
    if ($javaExe) {
        Write-Host "Java encontrado en $javaExe (version $mayor)."
    }
    Write-Host "junit-gen necesita Java 17 o superior (JDK) en el PATH o en JAVA_HOME. Instala un JDK 17+ y vuelve a ejecutar este script."
    exit 1
}

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    Write-Host "No se encuentra $jar. Descarga el repositorio completo (incluye plugin\junit-gen.jar)."
    exit 1
}

$argumentos = @('-cp', $jar, $instalador, '--repo', $repo, '--java-cmd', $javaCmd)
if ($McpJson) {
    $argumentos += @('--vscode-mcp', (Limpiar-Ruta $McpJson))
}
if ($CopilotDir) {
    $argumentos += @('--copilot-dir', (Limpiar-Ruta $CopilotDir))
}

& $javaExe @argumentos
$codigo = $LASTEXITCODE
if ($codigo -ne 0) {
    Write-Host ""
    Write-Host "La instalacion no termino correctamente (codigo $codigo)."
    Write-Host "Pista: el instalador necesita un JDK (no solo un JRE). La configuracion manual esta en docs/MANUAL.md."
}
exit $codigo
