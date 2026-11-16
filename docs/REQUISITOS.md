# Proyecto: junit-gen — Generador de tests unitarios asistido por IA (requisitos originales del usuario)

## Contexto y objetivo

Construir una herramienta llamada **junit-gen** que genere tests unitarios (JUnit) para cualquier proyecto Java. Principio fundamental, separar responsabilidades:

- **La IA (Copilot) solo decide QUÉ probar y CON QUÉ DATOS**: deduce la estructura común de los tests existentes y propone los casos de prueba con sus datos, stubs, resultados esperados y verificaciones.
- **La herramienta Java hace todo lo demás**: escanea el proyecto, valida, evita duplicados, escribe el código de los tests con una plantilla uniforme y, opcionalmente, los ejecuta.

La IA nunca escribe directamente los ficheros .java de test. Produce una especificación estructurada (spec en JSON) y la herramienta genera el código a partir de ella. Así el estilo es siempre uniforme, el resultado es revisable y regenerar no consume IA.

## Forma de la herramienta

- Un único JAR ejecutable, desarrollado en Java 17, con Maven.
- Se distribuye junto a un JDK 17 portable en su propia carpeta, para no depender del Java instalado en el sistema ni del Java del proyecto analizado.
- Funciona de dos modos:
  1. **Servidor MCP (stdio)**: modo principal. Se registra en GitHub Copilot y Copilot en modo Agent usa sus herramientas. Debe seguir el estándar MCP sin depender de funciones exclusivas de VS Code, para que funcione también en IntelliJ, Eclipse y Visual Studio.
  2. **Línea de comandos**: con los comandos scan, generate y verify, como alternativa si MCP está bloqueado por políticas de empresa. En este modo, cualquier IA puede generar el spec usando un prompt incluido en la carpeta de la herramienta.

## Seguridad: el proyecto analizado es de solo lectura

Regla obligatoria de diseño. Tiene prioridad sobre cualquier otra instrucción.

**junit-gen NUNCA modifica nada dentro del proyecto analizado. Solo crea archivos en la carpeta de salida indicada por el usuario.**

### Qué está permitido y qué no
- **Proyecto analizado: solo lectura.** Puede leer código fuente, tests, pom.xml, build.gradle, ficheros XML de configuración y junit-gen.yml. No puede crear, modificar, mover ni borrar ningún archivo o carpeta dentro de él: ni código, ni tests, ni pom.xml o build.gradle, ni configuración, ni la carpeta target o build, ni archivos ocultos (.git, .idea, .vscode…).
- **Carpeta de salida: único lugar de escritura.** Todo lo que genera la herramienta (tests, spec.json, informe.md, archivos temporales y compilados de la verificación) va exclusivamente a la carpeta de salida elegida por el usuario.
- **Nada fuera de esas dos rutas**, salvo la propia carpeta de instalación de la herramienta y la caché normal de dependencias de Maven o Gradle.

### Comprobaciones obligatorias antes de escribir
1. **Normalizar la ruta de salida** antes de usarla: ruta absoluta, sin "..", con enlaces simbólicos y accesos directos resueltos.
2. **Rechazar la operación** con un mensaje claro si la carpeta de salida es la misma que la del proyecto, está dentro del proyecto o contiene al proyecto.
3. **Comprobar cada archivo antes de escribirlo**: su ruta final tiene que estar dentro de la carpeta de salida. Si no lo está, la herramienta aborta y no escribe nada.
4. **No borrar nada**, ni siquiera en la carpeta de salida, salvo sus propios archivos temporales de verificación. Los tests ya generados solo se amplían, nunca se eliminan sin confirmación del usuario.

### Verificación de tests sin tocar el proyecto
- No ejecutar comandos que escriban en el proyecto, como mvn compile, mvn test, mvn clean, mvn install, gradle build o similares.
- Solo se permiten comandos de lectura, como obtener el classpath con dependency:build-classpath.
- Si el proyecto ya está compilado, usar sus clases compiladas solo para leerlas.
- Si no lo está, compilar las fuentes del proyecto en una carpeta temporal dentro de la carpeta de salida. Si eso no es posible, pedir al usuario que compile el proyecto él mismo.
- Compilar y ejecutar los tests siempre en una carpeta temporal dentro de la carpeta de salida.

### Instrucciones para el agente (Copilot) — deben ir en el prompt MCP y en el prompt sin MCP
- **El agente tampoco puede editar archivos del proyecto analizado**, ni con sus propias herramientas de edición ni ejecutando comandos de terminal, aunque crea que eso arreglaría un test. Si un test falla por un problema del proyecto, lo informa al usuario y no lo toca.
- **No hacer operaciones de git** sobre el proyecto: ni commit, ni checkout, ni stash, ni nada que cambie su estado.
- **Mover los tests al proyecto es siempre un paso manual del usuario.**

### Tandas de N servicios (sin paradas)
- Mientras la tanda tenga trabajo pendiente, toda respuesta de herramienta empieza por la línea `accionRequerida: NO HAS TERMINADO (servicio k de N): llama ahora a …` con la llamada exacta; el agente no escribe al usuario hasta `respuestaFinal`.
- Puerta de plan: no se empieza ni se pre-lee otro servicio del plan mientras el servicio en curso no tenga todos sus casos por método (`metodo=N`).
- Salvaguarda anti-bucle: tras 2 llamadas a `generar_tests` sin casos nuevos o 4 specs rechazados seguidos, el servicio se cierra como incompleto y la tanda sigue; el plan siempre llega a `restantes = 0`.
- Durante la generación no se corrige nada: los casos que fallan se apuntan en `tanda.json` (`arreglos`) y la cabecera muestra solo el contador.
- Fase de arreglo al terminar todos los servicios: junit-gen corrige primero solo lo mecánico, sin IA (valores sin `eq(...)` mezclados con matchers, respuestas simuladas que el código no usa, auxiliar inexistente con un único dato declarado de ese tipo; anotado como «arreglado automáticamente»); el resto llega agrupado por servicio (`casos` con error, pista, pistas, spec sin textos y `codigo`; con un solo caso, `caso` + `spec`), autosuficiente (no se llama a `detalle_servicio` salvo `codigo.masCodigo`), y se corrige en UNA llamada `arreglar_caso {casos: [{caso, spec, motivo}]}` que recompila y ejecuta una vez esa clase y devuelve `resultadoAnterior.resultados`; máximo 3 intentos y apartado automático. No se rebaja el test: corregir la preparación; cambiar `igual` por `contiene`/`noNulo`, quitar aserciones o verificaciones o cambiar el escenario (valor ↔ excepción) exige un `motivo` que cite al menos dos nombres reales del código (si no, `DEBILITA_TEST`; copiar el valor obtenido sin justificar, `COPIA_SIN_JUSTIFICAR`) y queda marcado «test menos estricto»; nunca se convierte un posible bug en un test que pasa (`registrar_analisis_ia` con `bug_servicio`). `mockStatic` sustituye todos los estáticos de la clase: los no simulados devuelven null/0/false y hay que añadirlos en `estaticos`. `servicio` siempre se aplica: los `imports` recibidos sustituyen por nombre simple a los guardados, las `fixtures` con el mismo nombre se reescriben y un cambio solo en la parte común cuenta como intento; si nada cambia, el rechazo `SIN_CAMBIOS` explica qué parte enviar. Si tras 2 correcciones de la parte común (o 3 rechazos) la clase de test sigue sin compilar por un error común, junit-gen aparta todos los casos pendientes de ese servicio (sin tocar el archivo, anotados en `informe.md`) y sigue con el siguiente servicio.
- El cierre incluye «Arreglados al final», «Arreglados automáticamente (sin IA)», «Posibles bugs», «Apartados» y «Tests menos estrictos (revisar en el informe)» en `respuestaFinal`, la sección «Arreglos al final» en `informe.md` y la tabla «Fase de arreglo» en «Medición de la tanda».
- `arreglos: inmediato` conserva la corrección servicio a servicio.

### Transparencia
- Al empezar, la herramienta muestra al usuario un aviso: "Modo solo lectura sobre [ruta del proyecto]. Los archivos se crearán únicamente en [ruta de salida]".
- El informe.md incluye la lista completa de archivos creados, con su ruta.

### Tests de seguridad de la propia herramienta
Incluir tests que demuestren que:
- se rechaza una carpeta de salida dentro del proyecto, igual al proyecto o que lo contenga;
- se rechazan rutas con ".." o enlaces que acaban dentro del proyecto;
- tras un flujo completo (escaneo, generación y verificación), el contenido y las fechas de modificación de todos los archivos del proyecto son idénticos a los de antes.

## Herramientas que debe exponer el servidor MCP

1. **escanear_proyecto(ruta)**: devuelve el informe del proyecto (versiones, servicios detectados con puntuación y motivo, tests existentes emparejados, métodos cubiertos y sin cubrir, estructura de los tests de ejemplo).
2. **detalle_servicio(clase)**: devuelve un servicio completo (métodos con firma y cuerpo, dependencias y cómo las obtiene, tipos auxiliares relacionados como DTOs, entidades y repositorios con sus constructores, campos y métodos). Va por separado para no saturar el contexto de la IA.
3. **validar_spec(spec)**: valida el spec y devuelve errores claros para que la IA los corrija.
4. **generar_tests(spec, carpetaSalida)**: escribe los tests en la carpeta indicada.
5. **ejecutar_tests(rutaProyecto, carpetaTests)**: compila y ejecuta los tests generados y devuelve el resultado por test.
6. Un **prompt predefinido** expuesto por MCP con las instrucciones del flujo completo, para que el usuario solo tenga que lanzarlo e indicar la carpeta de salida.

## Fase 1 — Escaneo (sin IA)

### Detección de versiones del proyecto
- Leer pom.xml o build.gradle para saber la versión de Java (8, 11, 17…), si usa JUnit 4 o JUnit 5, la versión de Mockito y si tiene AssertJ.
- Si el proyecto no tiene JUnit o Mockito como dependencias de test, avisar.
- El análisis del código fuente debe funcionar con cualquier versión de Java, aunque la herramienta se ejecute con Java 17.

### Detección de servicios por puntuación (con o sin anotaciones)
La detección NO debe depender solo de anotaciones (Spring antiguo con XML, EJB, Java sin framework). Cada clase recibe una puntuación según varias señales:
- **Anotaciones** (peso alto): @Service, @Component, @Transactional, @Stateless, @Named.
- **Registrada como bean sin anotación** (peso alto): método @Bean en una clase @Configuration o <bean> en un applicationContext.xml.
- **Nombre** (peso medio): termina en Service, ServiceImpl, Manager, UseCase, Facade, Business, Bo, Handler.
- **Paquete** (peso medio): service, services, business, application, usecase, core, logic.
- **Estructura** (peso medio): implementa una interfaz con nombre relacionado y recibe dependencias como repositorios, DAOs o clientes.
- **Lógica real** (peso medio): métodos públicos con condiciones, bucles, llamadas a dependencias o lanzamiento de excepciones, no solo getters y setters.

Se descartan entidades, DTOs, records solo de datos, controllers, repositorios (interfaces que extienden JpaRepository o similares), DAOs, clases de configuración, excepciones, enums y la clase main.

Archivo opcional **junit-gen.yml** en la raíz del proyecto analizado para incluir o excluir clases y paquetes por patrón, elegir el estilo de aserciones, indicar el JDK del proyecto y decidir qué se hace con los fallos de aserción (`fallosAsercion: revisarIa | preguntar`, por defecto `revisarIa`).

### Cómo obtiene cada servicio sus dependencias
Registrar para cada dependencia cómo se obtiene (determina el montaje del test):
- Por constructor o setter → mocks con inyección normal.
- Por campo, con @Autowired, @Inject, @Resource o sin anotación → inyección por reflexión.
- Creada con new dentro de la clase → mockConstruction si la versión de Mockito lo permite; si no, marcar el servicio como "requiere refactor".
- Llamadas estáticas → mockStatic si la versión lo permite, o dejarlas reales si son puras.
- Sin dependencias → tests directos sin mocks.

### Emparejamiento con tests existentes
1. **Por nombre** (primer filtro): normalizar quitando los sufijos Test, Tests, IT e Impl. Ej.: Prueba1ServiceTest y Prueba1ServiceImplTest corresponden a Prueba1ServiceImpl.
2. **Por la clase que realmente instancia el test**: tipo de @InjectMocks o el new que hace. Cubre tests cuyo nombre no sigue la convención.
3. **Por métodos cubiertos**: qué métodos del servicio se llaman dentro de los tests → qué métodos ya tienen tests y cuáles no.

El informe del escaneo se muestra al usuario antes de seguir, con cada servicio, su puntuación, el motivo de la detección, su test emparejado y el estado de cada método (con casos existentes o sin tests). El usuario confirma o ajusta la selección.

## Fase 2 — Análisis (IA de Copilot)

- La IA deduce la **estructura común** a partir de los tests de ejemplo: anotaciones de clase, forma de declarar mocks y la clase bajo prueba, estilo de aserciones, patrón de nombres de los métodos de test, uso de @DisplayName y comentarios given/when/then.
- Para cada servicio, y solo para los métodos o escenarios que faltan, la IA propone los **casos de prueba**: escenario, descripción, datos de entrada, stubs de las dependencias, llamada al método, resultado esperado o excepción esperada, aserciones y verificaciones.
- Se le pasa la lista de casos existentes para que no los proponga.
- Los datos deben ser compatibles con la versión de Java y las librerías del proyecto (p. ej. sin List.of, var ni records en Java 8; sin assertThrows en un JUnit 4 antiguo).
- Se trabaja servicio a servicio para no saturar el contexto.

## Fase 3 — Validación y duplicados (sin IA)

- Validar que el spec está completo y bien formado y que es compatible con la versión del proyecto. Errores con mensajes claros; la IA corrige el spec.
- **Duplicados**, comprobados por la herramienta y no por la IA, con una huella normalizada de cada caso (método probado, argumentos, stubs y resultado esperado, sin importar nombres de variables ni del método de test), en tres niveles:
  1. Contra los tests que ya existen en el proyecto.
  2. Dentro del propio spec.
  3. Contra lo generado en ejecuciones anteriores en la carpeta de salida.
- Opcional: usar cobertura con JaCoCo para descartar casos que no cubren ninguna rama nueva.

## Fase 4 — Generación (sin IA)

- Un test por servicio, en la carpeta de salida indicada, en una carpeta por servicio (`<módulo>\<Servicio>\`); el paquete se conserva en el código y la ruta destino se indica en la cabecera y en informe.md.
- Plantilla adaptada a la versión: JUnit 4 o JUnit 5, Mockito con runner o con extensión, AssertJ o aserciones de JUnit.
- Guardar el spec.json en la carpeta de salida, para poder revisarlo, retocarlo y regenerar sin IA.
- Generar un informe.md con lo generado, lo descartado como duplicado y los motivos, y los servicios marcados como "requiere refactor".
- En ejecuciones posteriores, solo añadir casos nuevos, sin reescribir los existentes.
- **La herramienta nunca escribe dentro del proyecto analizado.**

## Fase 5 — Verificación opcional

- Al terminar la generación los tests se prueban siempre, sin preguntar (clave `verificacion` de junit-gen.yml: `siempre` | `preguntar` | `nunca`). Solo con `preguntar` se pregunta con botones **[Probar tests] [Descartar]** (elicitation MCP; si el IDE no la soporta, en el chat).
- Al probar:
  - Obtener el classpath del proyecto con Maven o Gradle, compilar los tests de la carpeta de salida contra las clases compiladas del proyecto y ejecutarlos, usando el JDK del proyecto y sin copiar ni modificar nada dentro del proyecto.
  - Devolver el resultado de cada test: pasa, falla o no compila, con su error.
  - **Errores de montaje** (no compila, falta un mock, un dato mal construido): la IA corrige el spec, no el .java, se regenera y se reintenta hasta un máximo de 3 veces. Por defecto (`arreglos: alFinal`) no se corrigen entre servicios: se apuntan en `tanda.json` y se arreglan en la fase de arreglo (primero lo mecánico, solo; después agrupados por servicio con una llamada a `arreglar_caso`) (solo cuentan como intento los fallos tras una corrección aplicada); con `arreglos: inmediato`, la nota y la puerta entre servicios obligan a corregirlos antes de seguir (la puerta nunca rechaza con datos viejos y su rechazo ya incluye errores, pistas e ids). Si la clase no compila, los errores se atribuyen a cada caso por sus líneas y el resto de casos se compila y ejecuta sin los culpables (solo un error fuera de los casos afecta a todos); cada error de compilación trae `pista` con las firmas reales de constructores y métodos. Tras 3 intentos junit-gen los aparta solo (`montaje_fallido`, bloque comentado con `// junit-gen:apartado motivo=…`, nunca borrado, anotado en informe.md) y la tanda continúa. Las cabeceras de acción no llevan listas de ids. Si un import del test no compila (clase inexistente o paquete equivocado), junit-gen lo corrige solo antes de pasar el error a la IA: lo sustituye por la clase correcta del JDK, las librerías o el proyecto si hay un candidato claro (o lo quita) y recompila, hasta 3 rondas; queda anotado en «Arreglos al final» de `informe.md`. Al validar el spec se comprueba que cada import existe: uno inexistente con un candidato claro se corrige (aviso `IMPORT_CORREGIDO`) y, si no, se rechaza con `IMPORT_NO_EXISTE` y los candidatos.
  - **Fallo de aserción** (el servicio devuelve algo distinto de lo esperado): NO se maquilla, puede ser un bug real. Por defecto (`fallosAsercion: revisarIa` en junit-gen.yml) la IA lo revisa sola, sin preguntar al usuario: relee el código del servicio y registra un veredicto con un motivo basado en el código — `error_ia` (test mal planteado: corrige el spec, se regenera y se vuelve a probar, máximo 3 intentos), `bug_servicio` (posible bug: el test se mantiene y el motivo va al informe) o `dudoso` si no puede decidir —; nunca pone como esperado el valor obtenido sin justificarlo con el código. Con `fallosAsercion: preguntar` se pregunta al usuario al final: **[Es un bug, mantener el test] [Ajustar lo esperado] [Eliminar caso]** (más «Revisar con IA» y «Revisar uno a uno en el chat»).
  - Añadir el resultado final al informe.md.

## Fase 6 — Entrega al proyecto

El usuario revisa los tests y los copia manualmente a src/test/java cuando le convenzan. La ruta destino de cada test está en su cabecera (`// Copiar a:`) y en la tabla «Dónde copiar cada test» de informe.md.

## Entregables

1. El proyecto Maven de junit-gen con el JAR ejecutable (modo MCP y modo línea de comandos).
2. Un README en español con instalación, configuración MCP para VS Code, IntelliJ y Eclipse, uso paso a paso, uso por terminal y ejemplo de junit-gen.yml.
3. El prompt para el modo sin MCP.
4. Tests de la propia herramienta, al menos de detección de servicios, emparejamiento, duplicados y generación (y los de seguridad descritos arriba).

## Prueba real (la lanza el usuario en VS Code + Copilot Agent)

- Proyecto a analizar: C:\proyectos\MiProyecto (contiene varios proyectos Maven: pedidos, facturacion).
- Carpeta de salida: C:\proyectos\MiProyecto-tests (separada; crearla si no existe).
- Instalar la herramienta en C:\herramientas\junit-gen y registrarla en el mcp.json de usuario de VS Code.
- Flujo: 1) escanear y mostrar informe antes de seguir; 2) generar spec, validar, generar tests en la carpeta de salida; 3) ofrecer probarlos y, si acepta, ejecutarlos sin modificar MiProyecto; 4) resumen: generado, descartado por duplicado, fallos y por qué.
- **Restricción: no modificar nada dentro de C:\proyectos\MiProyecto.**
