# Guía: DistribucionEnviosPorDia

Utilidad para agrupar envíos por fecha, contar envíos diarios y encontrar el día más cercano a un objetivo. En el pipeline actual se usa para elegir el día histórico más cercano al nivel de carga objetivo.

## Características principales

- ✅ Agrupación eficiente por fecha (O(n) una sola vez)
- ✅ Búsqueda rápida del día más cercano a objetivo
- ✅ Top N días más cercanos
- ✅ Estadísticas de distribución
- ✅ Análisis de extremos (mín/máx)

## Uso básico

### 1. Crear instancia
```java
DistribucionEnviosPorDia distribucion = 
    new DistribucionEnviosPorDia(datos.getPaquetes());
```

### 2. Encontrar día cercano a objetivo
```java
int objetivo = 14000;  // 14,000 envíos
DiaSeleccionado resultado = distribucion.encontrarDiaMasCercano(objetivo);

System.out.println("Fecha: " + resultado.fecha);
System.out.println("Envíos: " + resultado.cantidad);
System.out.println("Diferencia: " + resultado.diferencia);
```

### 3. Top N días
```java
List<DiaSeleccionado> top5 = distribucion.encontrarDiasMasCercanos(14000, 5);
for (DiaSeleccionado dia : top5) {
    System.out.println(dia.toReporte());
}
```

## Métodos principales

| Método | Retorna | Descripción |
|--------|---------|-------------|
| `encontrarDiaMasCercano(int target)` | `DiaSeleccionado` | Día con cantidad más cercana al objetivo |
| `encontrarDiasMasCercanos(int target, int n)` | `List<DiaSeleccionado>` | Top N días más cercanos |
| `obtenerCantidadEnvios(LocalDate fecha)` | `int` | Cantidad de envíos en una fecha |
| `obtenerDistribucion()` | `Map<LocalDate, Integer>` | Mapa completo fecha → cantidad |
| `obtenerDiaMaximo()` | `DiaSeleccionado` | Día con máxima carga |
| `obtenerDiaMinimo()` | `DiaSeleccionado` | Día con mínima carga |
| `calcularEstadisticas()` | `EstadisticasDistribucion` | Análisis agregado |

## Clases de soporte

### DiaSeleccionado
Resultado de búsqueda con:
- `fecha`: LocalDate del día
- `cantidad`: Número de envíos
- `diferencia`: Diferencia con objetivo
- `envios`: List<Paquete> con los envíos

Método: `toReporte()` - Formato legible

### EstadisticasDistribucion
- `totalEnvios`: Total en el período
- `diasConEnvios`: Cantidad de días únicos
- `promedioDiario`: Media de envíos/día
- `minimoDiario`, `maximoDiario`: Rangos
- `desviacionEstandar`: Variabilidad

Método: `toReporte()` - Resumen formateado

## Ejemplos de uso

### Experimento 1: Día típico
```java
EstadisticasDistribucion stats = distribucion.calcularEstadisticas();
int objetivoTipico = (int) stats.promedioDiario;
DiaSeleccionado resultado = distribucion.encontrarDiaMasCercano(objetivoTipico);
```

### Experimento 2: Carga media
```java
DiaSeleccionado cargaMedia = distribucion.encontrarDiaMasCercano(10000);
```

### Experimento 3: Análisis comparativo
```java
int[] cargas = {5000, 10000, 15000};
for (int carga : cargas) {
    DiaSeleccionado dia = distribucion.encontrarDiaMasCercano(carga);
    // Ejecutar experimento con este día
}
```

## Optimización

Excelente rendimiento con grandes datasets:
- Agrupación: O(n) una sola vez en construcción
- Búsqueda: O(d) donde d = número de días
- Memory: O(d + n) con HashMap

**Tipicamente**: Construcción < 100ms, búsquedas < 5ms

## Archivo completo
```
src/tasf/core/DistribucionEnviosPorDia.java
```


La carpeta `src/tasf/examples/` ya no forma parte del proyecto; esta guía describe solo la clase activa.
