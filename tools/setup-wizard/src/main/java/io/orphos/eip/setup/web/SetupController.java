package io.orphos.eip.setup.web;

import io.orphos.eip.setup.dto.SetupDtos.ExternalDb;
import io.orphos.eip.setup.dto.SetupDtos.Functionality;
import io.orphos.eip.setup.dto.SetupDtos.InstallPlan;
import io.orphos.eip.setup.dto.SetupDtos.CreateDbRequest;
import io.orphos.eip.setup.dto.SetupDtos.CreateDbResult;
import io.orphos.eip.setup.dto.SetupDtos.AiProvider;
import io.orphos.eip.setup.dto.SetupDtos.ExistingInstall;
import io.orphos.eip.setup.dto.SetupDtos.InstallResult;
import io.orphos.eip.setup.dto.SetupDtos.LocalModel;
import io.orphos.eip.setup.dto.SetupDtos.RunRequest;
import io.orphos.eip.setup.dto.SetupDtos.Secrets;
import io.orphos.eip.setup.dto.SetupDtos.SetupConfig;
import io.orphos.eip.setup.dto.SetupDtos.ValidationResult;
import io.orphos.eip.setup.service.AiCatalog;
import io.orphos.eip.setup.service.ConfigWriter;
import io.orphos.eip.setup.service.ArcadeDbProvisioner;
import io.orphos.eip.setup.service.DbValidator;
import io.orphos.eip.setup.service.ExistingConfig;
import io.orphos.eip.setup.service.FunctionalityCatalog;
import io.orphos.eip.setup.service.PlatformRunner;
import io.orphos.eip.setup.service.SecretGenerator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * REST surface behind the wizard UI. The functionality catalogue and the
 * install-plan resolution are server-authoritative so the browser can't drift
 * from what the platform will actually start.
 */
@RestController
@RequestMapping("/api")
public class SetupController {

    private final FunctionalityCatalog catalog;
    private final SecretGenerator secretGenerator;
    private final DbValidator dbValidator;
    private final ConfigWriter configWriter;
    private final PlatformRunner platformRunner;
    private final ExistingConfig existingConfig;
    private final ArcadeDbProvisioner arcadeDbProvisioner;
    private final AiCatalog aiCatalog;

    public SetupController(FunctionalityCatalog catalog, SecretGenerator secretGenerator,
                          DbValidator dbValidator, ConfigWriter configWriter,
                          PlatformRunner platformRunner, ExistingConfig existingConfig,
                          ArcadeDbProvisioner arcadeDbProvisioner, AiCatalog aiCatalog) {
        this.catalog = catalog;
        this.secretGenerator = secretGenerator;
        this.dbValidator = dbValidator;
        this.configWriter = configWriter;
        this.platformRunner = platformRunner;
        this.existingConfig = existingConfig;
        this.arcadeDbProvisioner = arcadeDbProvisioner;
        this.aiCatalog = aiCatalog;
    }

    /** Selectable local Ollama models (config-driven catalogue). */
    @GetMapping("/ai/local-models")
    public List<LocalModel> localModels() {
        return aiCatalog.localModels();
    }

    /** Selectable external AI providers (config-driven; env-var names stay server-side). */
    @GetMapping("/ai/providers")
    public List<AiProvider> aiProviders() {
        return aiCatalog.providersForUi();
    }

    /** The selectable functionalities (server-authoritative catalogue). */
    @GetMapping("/functionalities")
    public List<Functionality> functionalities() {
        return catalog.all();
    }

    /** Resolve a functionality selection into the concrete service/plan preview. */
    @PostMapping("/plan")
    public InstallPlan plan(@RequestBody List<String> functionalities) {
        return catalog.resolve(functionalities);
    }

    /** A fresh, fully-populated secret set for the "regenerate" action. */
    @GetMapping("/secrets/generate")
    public Secrets generateSecrets() {
        return secretGenerator.freshSecrets();
    }

    /**
     * Drift guard: if a prior {@code .env} exists, return its secrets so the
     * wizard reuses the values the volumes were initialized with (rotating them
     * would break Keycloak/DB auth). Otherwise return a freshly generated set.
     */
    @GetMapping("/existing-install")
    public ExistingInstall existingInstall() {
        return existingConfig.readExistingSecrets()
                .map(s -> new ExistingInstall(true, existingConfig.envPath(), s))
                .orElseGet(() -> new ExistingInstall(false, existingConfig.envPath(),
                        secretGenerator.freshSecrets()));
    }

    /** Live-validate an external ArcadeDB connection. */
    @PostMapping("/validate/arcade")
    public ValidationResult validateArcade(@RequestBody ExternalDb db) {
        return dbValidator.validateArcade(db.url(), db.user(), db.password());
    }

    /** Live-validate an external Postgres (JDBC) connection. */
    @PostMapping("/validate/postgres")
    public ValidationResult validatePostgres(@RequestBody ExternalDb db) {
        return dbValidator.validatePostgres(db.url(), db.user(), db.password());
    }

    /** Create an ArcadeDB database (empty, or with the default KG structure). */
    @PostMapping("/db/arcade/create")
    public CreateDbResult createArcadeDb(@RequestBody CreateDbRequest req) {
        return arcadeDbProvisioner.create(req.url(), req.user(), req.password(), req.database(), req.withSchema());
    }

    /** Where the wizard will write .env / orphos.config.json (shown in the UI). */
    @GetMapping("/target-dir")
    public String targetDir() {
        return configWriter.targetDir();
    }

    /**
     * Finalise: fill any blank secrets, resolve the plan, and write .env +
     * orphos.config.json. Returns the plan and the paths written.
     */
    @PostMapping("/install")
    public InstallResult install(@RequestBody SetupConfig config) {
        Secrets effective = secretGenerator.fillBlanks(config.secrets());
        InstallPlan plan = catalog.resolve(config.functionalities());
        return configWriter.write(config, effective, plan);
    }

    /**
     * Brings the platform up (after install) and streams the combined build/boot
     * log back line-by-line. Runs {@code start.sh} for mode "full", else
     * {@code startmodules.sh} with the selected functionalities (validated
     * against the catalogue).
     */
    @PostMapping(value = "/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public StreamingResponseBody run(@RequestBody RunRequest req) {
        List<String> feats = (req.functionalities() == null ? List.<String>of() : req.functionalities())
                .stream().filter(catalog::isKnown).toList();
        String mode = req.mode();
        return out -> platformRunner.stream(mode, feats, out);
    }
}
