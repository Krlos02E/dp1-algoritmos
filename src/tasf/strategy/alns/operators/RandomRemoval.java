package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.*;

public class RandomRemoval implements DestructionOperator {

    private final Random random;

    public RandomRemoval(Random random) {
        this.random = random;
    }

    @Override
    public Set<String> destroy(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                               Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                               int cantidad) {
        Set<String> destruidos = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>(propuesta.keySet());
        Collections.shuffle(ids, random);
        for (int i = 0; i < Math.min(cantidad, ids.size()); i++) {
            destruidos.add(ids.get(i));
        }
        return destruidos;
    }

    @Override
    public String nombre() {
        return "RandomRemoval";
    }
}
