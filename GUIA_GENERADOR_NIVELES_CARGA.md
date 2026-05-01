# GeneradorNivelesCarga - Guía Rápida

## 📋 ¿Qué es?

Clase Java que genera niveles de carga para experimentos logísticos. En el pipeline actual alimenta `StandardExperimentPipeline`, que compara ACO y ALNS sobre varios niveles de carga derivados de una capacidad máxima diaria.

---

## 🚀 Uso Rápido

### Opción 1: Usar valores predeterminados (MÁS SIMPLE)

```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(15000);  // 15k maletas máximo

// Genera 5 niveles entre 20% y 70%
List<Integer> niveles = gen.generarNivelesDefecto();
// Resultado: [3000, 5250, 7500, 9750, 12000]
```

### Opción 2: Especificar rango y cantidad

```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(20000);

// Generar 4 niveles entre 30% y 80%
List<Integer> niveles = gen.generarNiveles(0.30, 0.80, 4);
// Resultado: [6000, 9333, 12667, 16000]
```

### Opción 3: Porcentajes exactos personalizados

```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(15000);

// Especificar exactamente qué porcentajes quieres
List<Integer> niveles = gen.generarNivelesPersonalizados(0.25, 0.50, 0.75);
// Resultado: [3750, 7500, 11250]
```

---

## 📊 Métodos Disponibles

| Método | Ejemplo | Resultado |
|--------|---------|-----------|
| **generarNivelesDefecto()** | `gen.generarNivelesDefecto()` | 5 niveles: 20%-70% |
| **generarNiveles(min%, max%, n)** | `gen.generarNiveles(0.30, 0.80, 4)` | 4 niveles entre 30%-80% |
| **generarNivelesPersonalizados(...)** | `gen.generarNivelesPersonalizados(0.25, 0.50, 0.75)` | Exactamente esos % |
| **generarCuartiles()** | `gen.generarCuartiles()` | 4 niveles: 25%, 50%, 75%, 100% |
| **generarDeciles()** | `gen.generarDeciles()` | 10 niveles: 10%, 20%, ..., 100% |
| **generarNivelesLogaritmico(min%, max%, n)** | `gen.generarNivelesLogaritmico(0.20, 0.70, 5)` | Más granular en bajos |
| **obtenerInfoNivel(capacidad)** | `gen.obtenerInfoNivel(5000)` | Info: capacidad, % y más |

---

## 💡 Ejemplos Prácticos

### Ejemplo 1: Experimento Simple (3 niveles)

```java
int capacidadMaxima = 15000;
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(capacidadMaxima);

List<Integer> niveles = gen.generarNiveles(0.20, 0.70, 3);
// Output: [3000, 6000, 9000] (aproximadamente 20%, 45%, 70%)

for (int nivel : niveles) {
    ejecutarExperimento(nivel);
}
```

### Ejemplo 2: 3 Escenarios (Bajo, Medio, Alto)

```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(20000);

List<Integer> bajo = gen.generarNiveles(0.20, 0.40, 3);   // 20-40%
List<Integer> medio = gen.generarNiveles(0.40, 0.60, 3);  // 40-60%
List<Integer> alto = gen.generarNiveles(0.60, 0.80, 3);   // 60-80%

// Guardar en archivo o ejecutar cada escenario
guardarConfiguracion("bajo", bajo);
guardarConfiguracion("medio", medio);
guardarConfiguracion("alto", alto);
```

### Ejemplo 3: Análisis Estadístico (Cuartiles)

```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(15000);
List<Integer> cuartiles = gen.generarCuartiles();

System.out.println("Q1: " + cuartiles.get(0));  // 25%
System.out.println("Q2: " + cuartiles.get(1));  // 50% (mediana)
System.out.println("Q3: " + cuartiles.get(2));  // 75%
System.out.println("Q4: " + cuartiles.get(3));  // 100%
```

### Ejemplo 4: Múltiples Días

```java
int[] capacidadesPorDia = {12000, 15000, 18000, 15000, 12000};

for (int capacidad : capacidadesPorDia) {
    GeneradorNivelesCarga gen = new GeneradorNivelesCarga(capacidad);
    List<Integer> niveles = gen.generarNivelesDefecto();
    
    // Usar los niveles para ese día
    procesarDia(niveles);
}
```

---

## 🎯 Casos de Uso Recomendados

| Caso | Método | Parámetros |
|------|--------|-----------|
| **Experimento simple** | `generarNiveles()` | 3-5 niveles, rango 20%-70% |
| **Análisis estadístico** | `generarCuartiles()` | Sin parámetros |
| **Análisis detallado** | `generarDeciles()` | Sin parámetros |
| **Porcentajes específicos** | `generarNivelesPersonalizados()` | Los % exactos que necesites |
| **Distribución no uniforme** | `generarNivelesLogaritmico()` | Para granularidad en bajos |

---

## 🔧 Personalización

### Cambiar el Rango (20%-70% → 15%-85%)

```java
List<Integer> niveles = gen.generarNiveles(0.15, 0.85, 5);
```

### Cambiar la Cantidad de Niveles

```java
List<Integer> pocos = gen.generarNiveles(0.20, 0.70, 3);    // 3 niveles
List<Integer> muchos = gen.generarNiveles(0.20, 0.70, 10);  // 10 niveles
```

### Cambiar la Distribución (Lineal → Logarítmica)

```java
// Lineal (uniforme)
List<Integer> lineal = gen.generarNivelesUniforme(0.20, 0.70, 5);

// Logarítmica (más granularidad en bajos)
List<Integer> log = gen.generarNivelesLogaritmico(0.20, 0.70, 5);
```

---

## 📐 Fórmulas

### Distribución Lineal (Uniforme)
```
capacidad[i] = capacidadMaxima × (min + (max - min) × i/(n-1))
```

Ejemplo: 5 niveles, 20%-70%, 15000 máx:
- Nivel 1: 15000 × 0.20 = 3000
- Nivel 2: 15000 × (0.20 + 0.50×0.25) = 5250
- Nivel 3: 15000 × (0.20 + 0.50×0.50) = 7500
- ...

### Distribución Logarítmica
```
log(capacidad[i]) = log(min) + (log(max) - log(min)) × i/(n-1)
```

Más granular en valores bajos, menos en altos.

---

## 📍 Ubicación en el Proyecto

```
src/tasf/
├── experiments/
│   └── GeneradorNivelesCarga.java          (clase principal)
```

---

## ✅ Validaciones

La clase valida automáticamente:

- ✓ Porcentajes entre 0 y 1
- ✓ Mínimo ≤ Máximo
- ✓ Cantidad de niveles ≥ 1
- ✓ Capacidad máxima > 0

Ejemplo de error:
```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(0);  // ❌ Error!
gen.generarNiveles(0.80, 0.20, 5);  // ❌ Error! Min > Max
```

---

## 🎬 Comenzar Ahora

**Lo más rápido:**
```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(15000);
List<Integer> niveles = gen.generarNivelesDefecto();
System.out.println(niveles);
```

**Con personalización:**
```java
GeneradorNivelesCarga gen = new GeneradorNivelesCarga(20000);
List<Integer> niveles = gen.generarNiveles(0.25, 0.75, 5);
for (int nivel : niveles) {
    System.out.println("Ejecutar con: " + nivel + " maletas");
}
```

---

La carpeta `src/tasf/examples/` fue eliminada; esta guía solo documenta la clase real del proyecto.
