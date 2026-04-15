# Estructura de Datos para Simulacion

Este proyecto usa la siguiente estructura organizada de datos:

- `data/input/aeropuertos/`
  - Coloca aqui el archivo de aeropuertos (UTF-16) con la palabra `Aeropuerto` en el nombre.
  - Ejemplo: `c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt`

- `data/input/vuelos/`
  - Coloca aqui `planes_vuelo.txt`.

- `data/input/envios/`
  - Coloca aqui los archivos de envios por aeropuerto.
  - Formato esperado del nombre: `_envios_XXXX_.txt` (XXXX = codigo OACI).
  - Ejemplos: `_envios_SKBO_.txt`, `_envios_EHAM_.txt`.

- `data/output/`
  - Carpeta para logs/resultados de simulacion.
  - Se crea un archivo `simulacion_yyyyMMdd_HHmmss.log` por cada corrida.

## Ejecucion

Desde la raiz del proyecto:

```bash
mkdir -p out
javac -d out $(find src/tasf -name '*.java')
java -cp out tasf.app.Main
```

Por defecto, la simulacion lee todos los envios de cada archivo.
Si quieres limitar, usa por ejemplo `--max-envios=100`.
Tambien puedes forzar lectura completa con `--max-envios=all`.
Si seleccionas `--algoritmo=AMBOS`, ALNS y ACO corren en paralelo.

Importante para resultados coherentes:
- Si cargas pocos `dias-vuelos`, muchos paquetes pueden quedar fuera de ventana temporal.
- El sistema ahora imprime diagnostico de paquetes fuera de la ventana de vuelos cargada.
- La simulacion usa por defecto una ventana de 5 dias posteriores a `--fecha`.
- Puedes cambiarla con `--dias-simulacion=N`.

Ejemplo de corrida de validacion rapida:

```bash
java -cp out tasf.app.Main --data-dir=/home/krlos/Desktop/PUCP/DP1/Algoritmos/datos --max-envios=100 --fecha=2026-01-02 --dias-simulacion=5 --algoritmo=AMBOS
```

Opcionalmente puedes usar una ruta distinta:

```bash
java -cp out tasf.app.Main --data-dir=/ruta/a/tu/carpeta/data
```

## Pruebas

```bash
java -cp out tasf.tests.PlannerTests
```

## Compatibilidad

El loader tambien acepta la estructura legacy:

- `planes_vuelo.txt`
- archivo de aeropuertos en la misma carpeta
- `_envios_preliminar_/`
