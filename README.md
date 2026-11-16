<div align="center">

# 🧪 junit-gen

### Pide los tests con tus palabras. Recíbelos compilados, ejecutados y explicados.

```
/generartest genérame los tests de PedidoService y UsuarioService
```

**La IA decide qué probar. junit-gen se encarga de que funcione.**
Sin tocar ni una línea de tu proyecto.

![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)
![JUnit 5](https://img.shields.io/badge/JUnit-5-25A162)
![Mockito](https://img.shields.io/badge/Mockito-%E2%9C%93-blue)
![MCP](https://img.shields.io/badge/MCP-stdio-purple)
![Maven | Gradle](https://img.shields.io/badge/Maven%20%7C%20Gradle-%E2%9C%93-informational)
![Licencia MIT](https://img.shields.io/badge/licencia-MIT-lightgrey)

</div>

---

## 💬 Pídelo como se lo pedirías a un compañero

No hace falta aprender comandos. Escribe `/generartest` y lo que quieres:

| Escribes | junit-gen entiende |
|---|---|
| `/generartest genérame los tests de 10 servicios` | 10 servicios, con sus funciones más importantes |
| `/generartest haz tests de cinco servicios` | 5 servicios (el número vale en cifra o en letra) |
| `/generartest genérame los tests de PedidoService y UsuarioService` | esos dos servicios |
| `/generartest prueba todas las funciones de UsuarioService` | ese servicio, sin dejarse ninguna función |
| `/generartest tests de PedidoService solo de crear y cancelar` | solo esas dos funciones |
| `/generartest continúa la tanda` | sigue donde se quedó |
| `/generartest cambia la carpeta de salida` | te pregunta dónde guardar los tests |

- **Mayúsculas, comillas, comas o la «y»: da igual.** `pedidoservice`, `PedidoService` o `"PedidoService"` son lo mismo.
- **Si escribes una interfaz**, prueba su implementación (`UsuarioService` → `UsuarioServiceImpl`).
- **Si un servicio no existe**, te lo dice y te sugiere los nombres parecidos. Con los que sí existen sigue adelante.
- **Si no queda claro lo que pides** («hazme unos tests»), te pregunta cuántos servicios o cuáles.

> La frase la interpreta **junit-gen**, no la IA. Por eso funciona igual de bien con un modelo potente que con uno más sencillo.

**¿Prefieres ir rápido?** También valen los atajos:

```
/generartest 5                                    → 5 servicios
/generartest PedidoService todas                  → todas sus funciones
/generartest PedidoService funciones=crear,cancelar
/generartest continuar
```

---

## ¿Por qué junit-gen?

Pedirle tests a una IA "a pelo" suele acabar igual: tests que no compilan, mocks inventados, imports que no existen y un `assertEquals` cambiado para que pase. Y tú revisando todo a mano. Y además es lento: la IA escribe cada archivo de test palabra a palabra.

### ⚡ Los tests los escribe una aplicación Java, no la IA

junit-gen es una **aplicación Java** que analiza tu proyecto y **genera ella misma el código de los tests**, en milisegundos. La IA solo le dice *qué* probar con unas pocas líneas de JSON; todo el código lo escribe, compila y ejecuta junit-gen.

- 🚀 **Hasta 2,7× más rápido** que si la IA escribiera cada test: la IA genera un spec corto en vez de archivos enteros, y en Claude Code los servicios se reparten entre subagentes que trabajan **en paralelo**.
- 💸 **Gasta mucha menos IA**: entre un 60 % y un 73 % menos de tokens y hasta un 81 % menos de coste en las pruebas medidas.
- 🎯 **Resultado uniforme y fiable**: mismo estilo en todos los tests, imports y firmas reales sacados de tu código, sin inventos.
- ♻️ **Regenerar no cuesta IA**: a partir del `spec.json` junit-gen vuelve a escribir los tests al instante.

**junit-gen cambia el reparto:**

| La IA (Copilot, Claude…) | junit-gen |
|---|---|
| Elige las funciones importantes de cada servicio | Escanea el proyecto y le da la estructura real de cada clase |
| Propone los casos: escenario, datos, mocks y resultado esperado | Valida, descarta duplicados y escribe los `.java` con una plantilla uniforme |
| Corrige los casos que fallan | Compila y ejecuta cada test en una carpeta temporal y le dice **exactamente** por qué falla |
| — | Arregla solo los fallos mecánicos, sin gastar IA |
| — | Rechaza los "arreglos" que rebajan el test |
| — | Te deja un informe en lenguaje llano |

> La IA **nunca escribe código Java**. Solo rellena un spec JSON. Por eso los tests salen uniformes, compilan y se pueden regenerar sin volver a gastar IA.

### 📊 Medido, no prometido

Mismo proyecto real (Spring Boot, 2 módulos), mismos servicios y mismo modelo (Claude Haiku 5.5, esfuerzo alto, Claude Code CLI). «Solo IA» = pedirle a la IA que escriba, compile y arregle los tests por su cuenta.

| Servicios | Solo IA | Con junit-gen | Diferencia |
|---|---|---|---|
| 5 | 6 min 48 s · 4,5 M tokens · 35/35 pasan | 5 min 02 s · 1,8 M tokens · 27/27 pasan | 1,4× más rápido · −60 % tokens |
| 15 | 14 min 17 s · 11,4 M tokens · 1,10 $ · 94/95 | **5 min 21 s · 3,1 M tokens · 0,20 $ · 71/71** | **2,7× más rápido · −73 % tokens · −81 % coste** |
| 25 | 12 min 45 s · 11,9 M tokens · 1,04 $ · 115/116 | **6 min 06 s · 4,2 M tokens · 0,26 $ · 128/129** | **2,1× más rápido · −64 % tokens · −75 % coste** |

Además, en esas pruebas la IA sola **modificó o sobrescribió tests que ya existían** en el proyecto; junit-gen no tocó ni un archivo. Los resultados varían entre ejecuciones (sobre todo con pocos servicios) y dependen del modelo y del proyecto.

---

## ✨ Qué consigues

- ✅ **Tests que compilan y pasan.** Cada test se compila y se ejecuta antes de dártelo.
- 🛠️ **Arreglo automático al final.** Si falla el test 3 del servicio 23, se apunta, se sigue con el resto y se arregla al final, uno a uno, recompilando.
- 🧠 **Los fallos mecánicos se arreglan sin IA:** matchers mezclados, stubs que no se usan, imports equivocados…
- 🛡️ **Guardia de calidad.** Si la IA intenta quitar una aserción o poner el valor que sale para que pase, junit-gen lo rechaza.
- 🐞 **Detecta posibles bugs.** Si el código no hace lo que debería, el test no se "arregla": se marca como *posible bug* para que lo revises.
- 📄 **Informe para humanos** (`informe.md`): qué se probó, por qué, qué falló, qué se arregló y dónde copiar cada test.
- ♻️ **Sin duplicados y sin pisar nada.** Los tests existentes solo se amplían; nunca se borran.
- 📦 **Tandas grandes.** «genérame los tests de 50 servicios» los hace del tirón; si se corta, «continúa la tanda».
- 🔒 **Solo lectura sobre tu proyecto.** Todo se escribe en una carpeta de salida aparte.

---

## 🚀 Qué pasa cuando lo pides

```
/generartest genérame los tests de 5 servicios
```

1. **Escanea** el proyecto (Maven o Gradle, multimódulo) y elige los servicios.
2. La IA **selecciona** las funciones con más lógica de negocio de cada uno.
3. La IA **propone los casos** y junit-gen **genera** los tests.
4. Cada servicio se **compila y ejecuta** en segundo plano mientras se sigue con el siguiente.
5. **Fase de arreglo:** lo que falló se corrige al final (primero lo mecánico, después la IA con pistas concretas).
6. Te devuelve el **resultado**:

| Resultado | Cifra |
|---|---|
| Casos generados | 25 |
| Ejecutados | 25 |
| Pasan | 25 |
| Descartados por duplicado | 7 |
| Arreglados al final | 8 |
| Posibles bugs | 0 |
| Servicios completados | 5 de 5 |

*(Ejecución real: 5 servicios de un proyecto Spring Boot multimódulo, unos 7 minutos.)*

---

## 📂 Qué genera

```
ejemploTest/
├── informe.md            ← el informe en lenguaje llano
├── spec.json             ← los casos (regenerables sin IA)
├── tanda.json            ← progreso, para retomar
├── perfil-proyecto.json  ← lo que junit-gen aprende de tu proyecto
├── PedidoService/
│   └── PedidoServiceImplTest.java
└── UsuarioService/
    └── UsuarioServiceImplTest.java
```

### Un test generado

Extracto abreviado de `PedidoService/PedidoServiceImplTest.java` del proyecto de ejemplo (el archivo real lleva además la cabecera, los imports y marcadores `// junit-gen:` que permiten ampliarlo sin reescribirlo):

```java
// Copiar a: src/test/java/com/ejemplo/tienda/pedidos/PedidoServiceImplTest.java
@ExtendWith(MockitoExtension.class)
class PedidoServiceImplTest {

    @Mock private PedidoRepository pedidoRepository;
    @Mock private StockClient stockClient;
    @InjectMocks private PedidoServiceImpl service;

    @Test
    @DisplayName("crear: lanza SinStockException si no queda stock del producto")
    void crear_sinStock() {
        // Preparación
        PedidoRequest request = new PedidoRequest(42L, 3);
        when(stockClient.disponible(42L)).thenReturn(0);

        // Ejecución y comprobación
        SinStockException ex = assertThrows(SinStockException.class, () -> service.crear(request));
        assertEquals("Producto 42 sin stock", ex.getMessage());
        verify(pedidoRepository, never()).save(any());
    }
}
```

### El informe

Para cada ejecución, el informe cuenta:

- **Qué funciones se han probado y por qué** las eligió la IA.
- **Qué comprueba cada test**, en una frase.
- **El resultado de cada test**: pasa, posible bug o apartado para revisar.
- **Arreglos al final**: qué fallaba, qué se cambió y en cuántos intentos.
- **Medición de la tanda**: tiempo y llamadas por servicio, para ver dónde se va el tiempo.
- **Dónde copiar cada test** en tu proyecto.

---

## 🔒 Tu proyecto, intacto

junit-gen está diseñado para que puedas lanzarlo sobre cualquier repositorio sin miedo:

- **Nunca crea, modifica ni borra nada dentro del proyecto analizado**: ni código, ni `pom.xml`, ni `target`, ni `.git`.
- **Solo escribe en la carpeta de salida** que elijas, y la rechaza si está dentro del proyecto.
- **No ejecuta `mvn compile`, `mvn test`, `gradle build` ni git.** Compila y prueba en una carpeta temporal propia.
- Hace una **foto del proyecto antes y después** de ejecutar los tests y avisa si algo cambia.
- Copiar los tests a `src/test/java` es **siempre un paso manual tuyo**.
- En Claude Code, el plugin además **impide al agente editar archivos o ejecutar comandos** mientras dura la tanda.

---

## 📦 Instalación

**Requisito:** Java 17 o superior (JDK) en el `PATH`.

### Claude Code (plugin)

```
/plugin marketplace add Ftejero21/junit-gen
/plugin install junit-gen@junit-gen
```

Abre Claude Code en la carpeta de tu proyecto Java y escribe, por ejemplo, `/generartest genérame los tests de 5 servicios`.

El comando completo es `/junit-gen:generartest`; el corto `/generartest` funciona mientras no haya otro comando con ese nombre.

### GitHub Copilot en VS Code

```bash
git clone https://github.com/Ftejero21/junit-gen.git
cd junit-gen
./instalar-copilot.sh
```

En Windows:

```
powershell -ExecutionPolicy Bypass -File .\instalar-copilot.ps1
```

Abre tu proyecto en VS Code, pon el chat en modo **Agent** y escribe `/generartest` con lo que quieras probar.

### Desde el código

```bash
mvn -DskipTests package    # genera target/junit-gen.jar
```

Funciona con cualquier cliente MCP por stdio: `java -jar junit-gen.jar mcp`.

---

## 🧪 Pruébalo con el proyecto de ejemplo

En [`ejemplo/`](ejemplo/) hay una tienda pequeña (Maven, Spring) con 5 servicios y sin tests. Ábrela en Claude Code o en VS Code y escribe:

```
/generartest genérame los tests de 2 servicios
```

Más detalles en [ejemplo/README.md](ejemplo/README.md).

---

## ⚙️ Configuración opcional (`junit-gen.yml`)

```yaml
porcentajeFunciones: 60      # % de funciones con lógica que se prueban por servicio
fallosAsercion: revisarIa    # revisarIa | preguntar
arreglos: alFinal            # alFinal | inmediato
verificacion: siempre        # siempre | preguntar | nunca
```

**Velocidad.** El spec que escribe la IA es compacto: omite `version`, `proyecto`, `estilo`, `id`, `claseTest`, `imports`, `parametros` y las listas vacías (junit-gen los completa y deduce el `id` de `metodo` + `escenario`), con `queHaceOriginal` una vez por función y `queComprueba` en cada caso. El escaneo en memoria solo se reutiliza si la huella de las fuentes y de los archivos de build no cambió. Variables de entorno opcionales: `JUNITGEN_PREESCANEO=1` (pre-escaneo en segundo plano al arrancar el servidor MCP; algo de CPU al arrancar) y `JUNITGEN_CARGA_DIRECTA=1` (marca las herramientas principales para clientes con carga diferida).

---

## ❓ Preguntas frecuentes

**¿Tengo que escribir los nombres exactos?**
No hace falta respetar mayúsculas. Si te equivocas en un nombre, junit-gen te sugiere los parecidos.

**¿Qué no entiende todavía?**
«Todos los servicios» sin un número (te pregunta cuántos) y las exclusiones como «menos PedidoService» (las ignora y te avisa).

**¿Qué IA necesito?**
Cualquiera que hable MCP: GitHub Copilot, Claude Code… Cuanto mejor el modelo, menos fallos que arreglar, pero junit-gen pone las mismas garantías con todos.

**¿Y si la IA se equivoca?**
junit-gen compila y ejecuta cada caso. Si falla, le da a la IA el error real y pistas concretas: firmas reales, imports correctos, qué stub sobra… Si tras 3 intentos no se arregla, el caso se aparta: queda comentado en el test y anotado en el informe, nunca se borra.

**¿Me cambia los tests que ya tengo?**
No. junit-gen nunca toca tu proyecto: todo lo que crea va a la carpeta de salida. Tampoco repite los casos que ya tienes. Si ya tienes `PedidoServiceTest.java`, te deja en la salida una versión ampliada con ese mismo nombre: tu archivo tal cual, sin cambiar ni una línea, y los casos nuevos añadidos al final, usando tus propios mocks (también si tu test usa `@SpringBootTest` y `@MockBean`). Antes de dártela comprueba que compila y que tus tests siguen pasando. Tú decides si la copias encima de la tuya. Si no se puede mezclar con seguridad, los casos nuevos van aparte, en `PedidoServiceGeneradoTest.java`, y el informe explica por qué.

**¿Funciona con Spring Boot y con proyectos multimódulo?**
Sí. Maven y Gradle, uno o varios módulos.

---

## 📚 Manual completo

Instalación manual, configuración de cada cliente, el plugin de Claude Code, uso por terminal, formato del spec y todas las opciones: [docs/MANUAL.md](docs/MANUAL.md).

---

## 📄 Licencia

[MIT](LICENSE)
