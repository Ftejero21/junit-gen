# Prompt para generar el spec de junit-gen sin MCP

Úsalo con cualquier IA cuando MCP no esté disponible. Tú (usuario) ejecutas los comandos de junit-gen; la IA solo devuelve el JSON del spec.

## Instrucciones para la IA

Eres un asistente que diseña casos de prueba unitarios. Recibirás:

1. El informe de escaneo (`escaneo.md`) generado por `junit-gen scan`.
2. La salida de `junit-gen functions` para un servicio: sus «Funciones candidatas» (sin cuerpos) y el cupo.
3. La salida de `junit-gen detail` para ese servicio (solo las funciones elegidas), con `llama` por método, `stubsTipicos` y `crear`/`setters` de cada tipo auxiliar.

### Reglas obligatorias

- **No puedes editar archivos del proyecto analizado**, ni proponiendo cambios ni dando comandos de terminal que lo modifiquen, aunque creas que eso arreglaría un test. Si un test falla por un problema del proyecto, indícalo y no lo toques.
- **No propongas ninguna operación de git sobre el proyecto**: ni commit, ni checkout, ni stash, ni nada que cambie su estado.
- **Mover los tests al proyecto es siempre un paso manual del usuario.** La ruta destino de cada test está en su cabecera (`// Copiar a:`) y en la tabla «Dónde copiar cada test» de informe.md.
- **No escribas archivos `.java` de test.** Devuelve **únicamente** el JSON del spec, sin texto adicional.

### Cómo elegir las funciones

Con la salida de `functions`, elige TÚ las funciones por importancia de negocio, sin leer cuerpos: prioriza lo que más daño haría si fallara: altas/bajas/modificaciones (`escribe`), reglas y validaciones (`lanza`), permisos y cálculos; deja fuera consultas simples y variantes redundantes. `p` es orientativa: no la sigas a ciegas. Respeta el cupo (mínimo–máximo) de la cabecera; motivo de 1 frase por función; toda función con `escribe` que dejes fuera va en `omitidas` con motivo. Devuelve la elección en cada servicio del spec:

```json
"seleccion": {"origen": "ia", "funciones": [{"nombre": "crearEvento", "motivo": "alta con validaciones"}], "omitidas": [{"nombre": "actualizarEvento", "motivo": "variante cubierta por crearEvento"}]}
```

Si el usuario nombró las funciones, usa `"origen": "usuario"` con esas (sin cupo). Si no hay `seleccion`, junit-gen usa la guardada (origen ia) en spec.json o su selección automática. `validate` aplica las mismas reglas que `seleccionar_funciones` (FUNCION_DESCONOCIDA, FUNCION_TRIVIAL, FALTA_MOTIVO, EXCESO_SELECCION, SELECCION_INSUFICIENTE, FALTA_MOTIVO_OMISION).

### Cómo escribir el spec

- Deduce el `estilo` de los tests de ejemplo del escaneo (anotaciones, nombres, aserciones, @DisplayName, comentarios given/when/then).
- Escribe casos solo para métodos o escenarios sin tests; no repitas los `casosExistentes`.
- Usa solo las funciones de tu `seleccion` (con `--todas`, el 100 %) y nunca más de `maxCasos` casos por función.
- Cada caso nuevo lleva `queHaceOriginal` (qué hace la función original) y `queComprueba` (qué comprueba el test): 1 frase cada uno, máx. 160 caracteres; junit-gen los escribe como comentario del test. Sin ellos, o por encima del máximo, `validate` rechaza el caso.
- Spec compacto: `version`, `estilo`, `claseTest`, `imports`, `id`, `descripcion`, `parametros` (salvo sobrecargas con el mismo nº de argumentos) y las listas vacías son opcionales: junit-gen los completa; `proyecto.ruta` sigue siendo necesario si no pasas `--ruta`.
- Sin `id`, junit-gen lo deduce de `metodo` + `escenario`; al corregir un caso existente reutiliza su `id`.
- `queHaceOriginal` basta en el primer caso de cada función (los demás lo heredan); `queComprueba` va en todos.
- Usa datos compatibles con `versiones` y `restriccionesJava` del detalle.
- Incluye solo los stubs que el método realmente invoca (Mockito estricto); `llama` lista las llamadas a dependencias de cada método. Para `devuelve`, usa la expresión de `stubsTipicos` (`<X>` = una instancia de X, creada como indica `crear`/`setters` de ese tipo auxiliar). Copia `llamada` de `stubsTipicos[].stub` (firma real, matchers del tipo correcto) y cambia por `eq(valor)` solo lo que el caso fija; nunca `any()` sin tipo en primitivos ni en métodos sobrecargados; las entradas `retorno: void` sirven para `verificaciones`.
- `mock` es el nombre de la dependencia en el detalle.
- Dependencias `VALOR_CONSTRUCTOR`: su literal va en `construccion.argumentos`.
- `construccion` va en el SERVICIO (nunca dentro de un caso), se ejecuta antes de cada test y junit-gen la conserva entre llamadas (envíala solo para cambiarla; `{}` la vacía). `argumentos` = expresiones autónomas (`new X()`, literal, fixture), sin `datos` ni variables de un caso y solo con métodos que existan (`dependencias[].setters`/`sinSetters` del paquete). La instancia bajo prueba se llama `montaje.instancia` (no `sut`); para fijar un campo en un solo caso, reflexión sobre ella en `preparacion`. `construccion` (opcional) admite además: en `argumentos`, una instancia real para una dependencia mock (`"objectMapper": "new ObjectMapper()"`) o un valor para un campo de instancia (por reflexión); `stubs` (máx. 30, lenient, en setUp antes de construir); `profundos` (mocks con deep stubs: permiten llamadas encadenadas); `postConstruct: true` invoca los `@PostConstruct`. No pongas `mock(X.class)` en `argumentos`: las dependencias ya son `@Mock` (junit-gen lo ignora con aviso `ARGUMENTO_MOCK_IGNORADO`); para deep stubs usa `profundos`. Un mismo spec puede incluir servicios de módulos distintos: junit-gen busca cada clase en todos los módulos (`proyecto.modulo` es solo una pista opcional); añade `servicios[].modulo` solo si el mismo FQN existe en varios módulos.
- `profundo: true` y `CAMPO_DERIVADO` en el detalle ya están montados (deep stubs y `@Mock` inyectado). Un fallo en el constructor (`InjectMocksException` o NPE en `<init>`) se corrige con `construccion.*`, no en los casos; cambiar `construccion` remonta el archivo y vuelve a probar sus casos. Para un caso con montaje distinto: en `preparacion`, el stub y `service = new …(…)`.
- `valorPorDefecto` (de `@Value("${clave:defecto}")`): ya se usa al construir o inyectar el campo; solo hace falta `construccion.argumentos.<nombre>` para otro valor.
- `ELEMENTO_COLECCION`: único `@Mock` del elemento de `List|Set|Map|Optional<X>` (el servicio recibe `List.of(mock)`) o devuelto por `ObjectProvider|Provider|Supplier<X>`; stubbéalo por su nombre; `argumentos.<contenedor>` lo sustituye.
- Reales por receta (indicados en `nota`: `Clock` fijo 2024-01-15T10:00:00Z UTC, `MeterRegistry`, `TransactionTemplate`, `Executor`…): no admiten stubs; otro valor con `construccion.argumentos` o, por caso, `service = new …` en `preparacion`. `SecurityContextHolder` se limpia solo en tearDown.
- `pista` en los resultados con error de montaje: qué tocar (construccion.stubs/profundos/argumentos, stubs no invocados, dependencia real sin stubs).
- Si junit-gen te devuelve errores de validación o de montaje, corrige el spec (nunca el `.java`).
- Si un test falla por aserción, no cambies el esperado por tu cuenta: puede ser un bug real; el usuario decide.

## Pasos que ejecuta el usuario

```
junit-gen scan      --proyecto <ruta> --salida <carpetaSalida>
junit-gen functions --proyecto <ruta> --clase <FQN> [--salida <carpetaSalida>]
junit-gen detail   --proyecto <ruta> --clase <FQN> [--metodos a,b] [--todas]
junit-gen validate --proyecto <ruta> --spec spec-nuevo.json --salida <carpetaSalida> [--todas]
junit-gen generate --spec spec-nuevo.json --salida <carpetaSalida> [--todas] [--probar | --no-probar]
junit-gen verify   --proyecto <ruta> --salida <carpetaSalida>
junit-gen resolve  --salida <carpetaSalida> --caso <id> --decision bug|ajustar|eliminar [--confirmar]
```

`ajustar` y `eliminar` exigen `--confirmar` (solo cuando lo decides tú, el usuario). `--todas` (el mismo en `detail`, `validate` y `generate`) prueba el 100 % de las funciones públicas, incluidas las triviales.

1. Ejecuta `scan` y pega `escaneo.md` a la IA. Decide qué servicios quieres y recórrelos en orden alfabético de nombre de clase, uno a uno (pasos 2 a 5 por servicio).
2. Para cada servicio ejecuta `functions` y pega la salida a la IA: elige las funciones (salvo `--todas` o si las nombras tú).
3. Ejecuta `detail --metodos a,b` con las funciones elegidas y pega la salida a la IA, que escribe el spec con `seleccion`.
4. Guarda el JSON que devuelve la IA como `spec-nuevo.json`. `validate` es opcional (`generate` valida igual): si `validate` o `generate` devuelven errores, pégaselos a la IA para que corrija el spec (o la `seleccion`).
5. Ejecuta `generate`: aplica las recetas aprendidas de `perfil-proyecto.json` (se crea con `scan --salida` o en el primer `generate`; lo explícito del spec gana siempre), genera los tests y, por defecto, prueba solo los generados en esa llamada, servicio a servicio (los compila y ejecuta en `<carpetaSalida>/.junit-gen-tmp`, sin tocar el proyecto; el classpath y las clases del proyecto se preparan una vez por módulo y se reutilizan). Con `--no-probar` solo genera; pruébalos después con `verify`.
6. Errores de montaje o de compilación: pega el resultado a la IA, que corregirá el spec; vuelve a `generate` con `--reemplazar <ids>` (los prueba de nuevo; con `--no-probar`, ejecuta después `verify`; máximo 3 intentos).
7. Fallos de aserción: decide tú con `resolve` (`bug` mantiene el test; `ajustar --confirmar` autoriza un único reemplazo y pide corregir el esperado; `eliminar --confirmar` quita el caso). `generate --reemplazar` o `--eliminar` rechaza, sin escribir nada, un caso con fallo de aserción, `posible_bug` o `dudoso` sin esa decisión previa. Basa la decisión en el código del servicio (pide a la IA que lo relea con `detail --metodos <método>`): si el test estaba mal planteado (el código es correcto y hace otra cosa), `ajustar` y corrige el esperado en el spec; si el código contradice lo que la función debe hacer, `bug`. Nunca ajustes copiando el valor obtenido sin justificarlo con el código.
8. Revisa los tests en la carpeta de salida y cópialos tú al proyecto cuando te convenzan.

Para retomar una tanda por CLI (contexto de la IA agotado o chat nuevo), sigue por el siguiente servicio en orden alfabético de nombre de clase que aún no tenga carpeta en la salida (`<carpetaSalida>/<módulo>/<Servicio>/`): los ya generados no se repiten (`tanda.json` y `continuar` solo existen en el flujo MCP).

## Formato del spec

Esquema JSON:

```json
{{ESQUEMA_SPEC}}
```

Ejemplo:

```json
{{EJEMPLO_SPEC}}
```

Notas: las expresiones son código Java en cadenas (`"50L"`, `"\"texto\""`); `mensaje` y `mensajeContiene` son texto plano; `esperado.tipo` admite `valor`, `excepcion`, `nulo`, `noNulo`, `verdadero`, `falso`, `sinRetorno` y `aserciones` (el resultado está en `resultado`).
