# Proyecto de ejemplo para junit-gen

Una tienda pequeña (Java 17, Maven, Spring) con **5 servicios** para probar junit-gen sin tocar ningún proyecto tuyo:

| Paquete | Servicio | Funciones |
|---|---|---|
| `pedidos` | `PedidoService` → `PedidoServiceImpl` | `crear`, `cancelar`, `buscar` |
| `usuarios` | `UsuarioService` → `UsuarioServiceImpl` | `buscar`, `activar`, `contarActivos` |
| `facturas` | `FacturaService` | `emitir`, `pagar`, `totalPendiente` |
| `catalogo` | `DescuentoService` | `descuentoPorVolumen`, `aplicar`, `precioFinal` |
| `carrito` | `CarritoService` | `agregar`, `total`, `vaciar` |

No trae tests: los genera junit-gen.

## Cómo probarlo

1. Instala junit-gen en tu cliente (ver el [README](../README.md)): el plugin de Claude Code o el script de GitHub Copilot.
2. Abre **esta carpeta** (`ejemplo/`) en Claude Code o en VS Code (chat de Copilot en modo **Agent**).
3. Escribe:

   ```
   /generartest genérame los tests de 2 servicios
   ```

   o, para una tanda completa:

   ```
   /generartest genérame los tests de 5 servicios
   ```

La primera vez junit-gen te pregunta dónde guardar los tests; por ejemplo, `ejemploTest` junto a esta carpeta.

## Requisitos

- Java 17 o superior (JDK).
- **Maven disponible** (en el `PATH` o en `MAVEN_HOME`/`M2_HOME`): junit-gen lo usa para resolver el classpath del proyecto y compilar los tests en una carpeta temporal. La primera vez descarga las dependencias del `pom.xml`.

## Nada se escribe aquí

junit-gen es de solo lectura sobre el proyecto analizado: **no crea, modifica ni borra nada dentro de `ejemplo/`** (ni `src/test`, ni `target`). Todo lo que genera (tests, `informe.md`, `spec.json`, `tanda.json`, `perfil-proyecto.json`) va a la carpeta de salida que elijas, que debe estar fuera de esta carpeta.
