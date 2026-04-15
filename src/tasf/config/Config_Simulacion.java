package tasf.config;

import java.time.Duration;
import java.time.LocalDateTime;

public class Config_Simulacion {
    private String aeropuertoHub = "SKBO";
    private Duration plazoMismoContinente = Duration.ofHours(24);
    private Duration plazoIntercontinental = Duration.ofHours(48);
    private Duration minimaConexion = Duration.ofMinutes(45);
    private Duration horizonteBusqueda = Duration.ofHours(72);
    private int iteracionesALNS = 120;
    private int iteracionesACO = 50;
    private int hormigasACO = 12;
    private int maxEscalas = 3;
    private int maxRutasPorPaquete = 8;
    private double porcentajeRuptura = 0.25;
    private int ventanaActualizacionPesos = 20;
    private double tasaAprendizajePesos = 0.35;
    private double alphaACO = 0.9;
    private double betaACO = 3.2;
    private double evaporacionFeromona = 0.25;
    private double depositoFeromonaQ = 80.0;
    private int topRutasACO = 4;
    private int hormigasEliteACO = 3;
    private double factorEliteACO = 1.5;
    private double factorGlobalBestACO = 2.5;
    private LocalDateTime finSimulacionUtcExclusivo;

    public String getAeropuertoHub() {
        return aeropuertoHub;
    }

    public void setAeropuertoHub(String aeropuertoHub) {
        this.aeropuertoHub = aeropuertoHub;
    }

    public Duration getPlazoMismoContinente() {
        return plazoMismoContinente;
    }

    public void setPlazoMismoContinente(Duration plazoMismoContinente) {
        this.plazoMismoContinente = plazoMismoContinente;
    }

    public Duration getPlazoIntercontinental() {
        return plazoIntercontinental;
    }

    public void setPlazoIntercontinental(Duration plazoIntercontinental) {
        this.plazoIntercontinental = plazoIntercontinental;
    }

    public Duration getMinimaConexion() {
        return minimaConexion;
    }

    public void setMinimaConexion(Duration minimaConexion) {
        this.minimaConexion = minimaConexion;
    }

    public Duration getHorizonteBusqueda() {
        return horizonteBusqueda;
    }

    public void setHorizonteBusqueda(Duration horizonteBusqueda) {
        this.horizonteBusqueda = horizonteBusqueda;
    }

    public int getIteracionesALNS() {
        return iteracionesALNS;
    }

    public void setIteracionesALNS(int iteracionesALNS) {
        this.iteracionesALNS = iteracionesALNS;
    }

    public int getIteracionesACO() {
        return iteracionesACO;
    }

    public void setIteracionesACO(int iteracionesACO) {
        this.iteracionesACO = iteracionesACO;
    }

    public int getHormigasACO() {
        return hormigasACO;
    }

    public void setHormigasACO(int hormigasACO) {
        this.hormigasACO = hormigasACO;
    }

    public int getMaxEscalas() {
        return maxEscalas;
    }

    public void setMaxEscalas(int maxEscalas) {
        this.maxEscalas = maxEscalas;
    }

    public int getMaxRutasPorPaquete() {
        return maxRutasPorPaquete;
    }

    public void setMaxRutasPorPaquete(int maxRutasPorPaquete) {
        this.maxRutasPorPaquete = maxRutasPorPaquete;
    }

    public double getPorcentajeRuptura() {
        return porcentajeRuptura;
    }

    public void setPorcentajeRuptura(double porcentajeRuptura) {
        this.porcentajeRuptura = porcentajeRuptura;
    }

    public int getVentanaActualizacionPesos() {
        return ventanaActualizacionPesos;
    }

    public void setVentanaActualizacionPesos(int ventanaActualizacionPesos) {
        this.ventanaActualizacionPesos = ventanaActualizacionPesos;
    }

    public double getTasaAprendizajePesos() {
        return tasaAprendizajePesos;
    }

    public void setTasaAprendizajePesos(double tasaAprendizajePesos) {
        this.tasaAprendizajePesos = tasaAprendizajePesos;
    }

    public double getAlphaACO() {
        return alphaACO;
    }

    public void setAlphaACO(double alphaACO) {
        this.alphaACO = alphaACO;
    }

    public double getBetaACO() {
        return betaACO;
    }

    public void setBetaACO(double betaACO) {
        this.betaACO = betaACO;
    }

    public double getEvaporacionFeromona() {
        return evaporacionFeromona;
    }

    public void setEvaporacionFeromona(double evaporacionFeromona) {
        this.evaporacionFeromona = evaporacionFeromona;
    }

    public double getDepositoFeromonaQ() {
        return depositoFeromonaQ;
    }

    public void setDepositoFeromonaQ(double depositoFeromonaQ) {
        this.depositoFeromonaQ = depositoFeromonaQ;
    }

    public int getTopRutasACO() {
        return topRutasACO;
    }

    public void setTopRutasACO(int topRutasACO) {
        this.topRutasACO = topRutasACO;
    }

    public int getHormigasEliteACO() {
        return hormigasEliteACO;
    }

    public void setHormigasEliteACO(int hormigasEliteACO) {
        this.hormigasEliteACO = hormigasEliteACO;
    }

    public double getFactorEliteACO() {
        return factorEliteACO;
    }

    public void setFactorEliteACO(double factorEliteACO) {
        this.factorEliteACO = factorEliteACO;
    }

    public double getFactorGlobalBestACO() {
        return factorGlobalBestACO;
    }

    public void setFactorGlobalBestACO(double factorGlobalBestACO) {
        this.factorGlobalBestACO = factorGlobalBestACO;
    }

    public LocalDateTime getFinSimulacionUtcExclusivo() {
        return finSimulacionUtcExclusivo;
    }

    public void setFinSimulacionUtcExclusivo(LocalDateTime finSimulacionUtcExclusivo) {
        this.finSimulacionUtcExclusivo = finSimulacionUtcExclusivo;
    }
}
