package br.com.wonder.agent.driver;

import br.com.wonder.agent.driver.wildfly.WildflyDriver;
import br.com.wonder.agent.model.driver.RuntimeDriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produtor de RuntimeDriver que seleciona baseado em driver.type na configuração.
 *
 * Cada driver usa @Typed(ConcreteDriver.class) para não conflitar com o RuntimeDriver
 * produzido aqui. Por isso o producer injeta os tipos concretos, não Instance<RuntimeDriver>.
 *
 * Ver docs/architecture/decisions/ADR-004-driver-abstraction.md
 */
@Slf4j
@ApplicationScoped
public class DriverProducer {

    @Produces
    @ApplicationScoped
    public RuntimeDriver produceDriver(
        @ConfigProperty(name = "driver.type", defaultValue = "wildfly") String driverType,
        Instance<WildflyDriver> wildflyDriver
    ) {
        log.info("Selecionando driver: {}", driverType);

        RuntimeDriver driver = switch (driverType) {
            case "wildfly" -> wildflyDriver.get();
            default -> throw new IllegalStateException("Driver não encontrado: " + driverType +
                ". Verifique driver.type em application.yaml");
        };

        log.info("Driver carregado: {} ({})", driverType, driver.getRuntimeType());
        return driver;
    }
}
