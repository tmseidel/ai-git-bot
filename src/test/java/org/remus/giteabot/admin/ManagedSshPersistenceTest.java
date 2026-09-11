package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.SshEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:managed-ssh-proof;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false", "spring.sql.init.mode=never"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ManagedSshPersistenceTest {
    private static final String SENTINEL = "test-only-upstream-secret-sentinel";
    @Autowired private GitIntegrationService service;
    @Autowired private GitIntegrationController controller;
    @Autowired private GitIntegrationRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private GiteaClientFactory factory;
    @MockitoBean private SshCommandService commands;
    private GiteaApiClient client;
    private GitIntegration saved;

    @BeforeEach
    void seed() {
        client = mock(GiteaApiClient.class);
        GitIntegration input = new GitIntegration();
        input.setName("managed-proof-" + UUID.randomUUID());
        input.setUrl("https://gitea.example.com");
        input.setToken("test-token");
        saved = service.save(input, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name-null", "name-blank", "name-long", "provider", "url-null", "url-blank",
            "url-long", "action", "username-long", "token-long"})
    void invalidRotationCannotChangeStateOrRevokeWorkingKey(String invalid) {
        makeManaged();
        GitIntegration before = readCommitted();
        GitIntegration input = readCommitted();
        input.setToken(null);
        input.setSshPrivateKey("replacement-key");
        input.setSshKnownHosts(null);
        switch (invalid) {
            case "name-null" -> input.setName(null);
            case "name-blank" -> input.setName(" ");
            case "name-long" -> input.setName("n".repeat(256));
            case "provider" -> input.setProviderType(null);
            case "url-null" -> input.setUrl(null);
            case "url-blank" -> input.setUrl(" ");
            case "url-long" -> input.setUrl("https://example.com/" + "u".repeat(256));
            case "action" -> input.setPostReviewAction(null);
            case "username-long" -> input.setUsername("u".repeat(256));
            case "token-long" -> input.setToken("t".repeat(200));
        }
        var flash = new RedirectAttributesModelMap();
        controller.save(input, input.getToken(), false, "replacement-key", null, false, flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertEquals(before, readCommitted());
        verifyNoInteractions(factory, commands, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "endpoint", "clear", "pending-cleanup"})
    void incompleteSshReplacementDoesNotRevokeKeyOrChangeCommittedState(String change) {
        makeManaged();
        if (change.equals("pending-cleanup")) {
            service.prepareManagedSshKeyRemoval(saved.getId());
        }
        GitIntegration before = readCommitted();
        GitIntegration input = readCommitted();
        input.setTransport(GitTransport.SSH);
        input.setToken(null);
        input.setSshPrivateKey(null);
        input.setSshKnownHosts("new-hosts");
        if (change.equals("token") || change.equals("endpoint")) {
            input.setToken("replacement-token");
        }
        if (change.equals("endpoint")) {
            input.setUrl("https://new.example.com");
        }
        var flash = new RedirectAttributesModelMap();

        controller.save(input, input.getToken(), false, null, "new-hosts", change.equals("clear"), flash);

        assertEquals(GitTransport.SSH, input.getTransport());
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertFalse(flash.getFlashAttributes().containsKey("success"));
        assertEquals(before, readCommitted());
        verifyNoInteractions(factory, commands, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSH", "HTTP"})
    void validReplacementKeepsRequestedTransportAfterHttpFirstCleanup(String transport) {
        makeManaged();
        GitIntegration input = readCommitted();
        input.setTransport(GitTransport.valueOf(transport));
        input.setToken(null);
        input.setSshPrivateKey(transport.equals("SSH") ? "replacement-key" : null);
        input.setSshKnownHosts(null);
        String storedToken = readCommitted().getToken();
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        when(client.getSshKeyIdsByTitle("tracked-title")).thenReturn(List.of(42L));
        doAnswer(call -> {
            GitIntegration pending = readCommitted();
            assertEquals(GitTransport.HTTP, pending.getTransport());
            assertNull(pending.getSshPrivateKey());
            assertTrue(pending.hasManagedSshKeyTracking());
            return null;
        }).when(client).deleteSshKey(42L);
        var flash = new RedirectAttributesModelMap();

        controller.save(input, null, false, input.getSshPrivateKey(), null, false, flash);

        assertTrue(flash.getFlashAttributes().containsKey("success"));
        assertFalse(flash.getFlashAttributes().containsKey("error"));
        GitIntegration after = readCommitted();
        assertEquals(GitTransport.valueOf(transport), after.getTransport());
        assertFalse(after.hasManagedSshKeyTracking());
        assertEquals(storedToken, after.getToken());
        if (transport.equals("SSH")) {
            assertEquals("replacement-key", service.decryptSshPrivateKey(after));
            assertEquals("hosts", after.getSshKnownHosts());
        } else {
            assertNull(after.getSshPrivateKey());
            assertNull(after.getSshKnownHosts());
        }
        verify(client).deleteSshKey(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB", "BITBUCKET"})
    void preflightHonorsProviderUrlDefaults(String provider) {
        GitIntegration input = readCommitted();
        input.setProviderType(RepositoryType.valueOf(provider));
        input.setUrl(null);
        input.setToken("replacement-token");
        service.validateSave(input, false, true);
        assertEquals(provider.equals("GITHUB") ? "https://github.com" : "https://bitbucket.org", input.getUrl());
    }

    @Test
    void registrationMarkerIsCommittedBeforeRequestAndSurvivesLostResponse(CapturedOutput output) {
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getAnySshCloneUrl()).thenReturn("git@gitea.example.com:owner/repo.git");
        when(client.getCurrentUserId()).thenReturn(17L);
        when(commands.scanHostKeys(anyString())).thenReturn(new SshCommandService.HostKeyScan(
                new SshEndpoint("gitea.example.com", 22), "hosts", List.of(), "scan"));
        when(commands.generateKeyPair(anyString())).thenReturn(new SshCommandService.SshKeyPair("private", "public"));
        when(client.createSshKey(anyString(), anyString())).thenAnswer(call -> {
            GitIntegration marker = readCommitted();
            assertEquals(GitTransport.HTTP, marker.getTransport());
            assertNull(marker.getSshPrivateKey());
            assertEquals(17L, marker.getSshRemoteKeyOwnerId());
            assertEquals(call.getArgument(0), marker.getSshRemoteKeyTitle());
            throw upstreamFailure();
        });
        var flash = new RedirectAttributesModelMap();
        controller.confirmSshSetup(saved.getId(), "scan", true, flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().hasManagedSshKeyTracking());
        assertNull(readCommitted().getSshRemoteKeyId());
        assertFalse(flash.getFlashAttributes().toString().contains(SENTINEL));
        assertFalse(output.getAll().contains(SENTINEL));
    }

    @Test
    void httpStateIsCommittedBeforeDeletionAndFailedCleanupIsRetryable(CapturedOutput output) {
        makeManaged();
        String storedToken = readCommitted().getToken();
        when(factory.getApiClient(any())).thenReturn(client);
        when(client.getCurrentUserId()).thenReturn(17L);
        when(client.getSshKeyIdsByTitle("tracked-title")).thenReturn(List.of(42L));
        doAnswer(call -> {
            GitIntegration pending = readCommitted();
            assertEquals(GitTransport.HTTP, pending.getTransport());
            assertNull(pending.getSshPrivateKey());
            assertNull(pending.getSshKnownHosts());
            assertEquals(42L, pending.getSshRemoteKeyId());
            assertEquals(17L, pending.getSshRemoteKeyOwnerId());
            assertEquals("tracked-title", pending.getSshRemoteKeyTitle());
            assertEquals(storedToken, pending.getToken());
            throw upstreamFailure();
        }).when(client).deleteSshKey(42L);
        var flash = new RedirectAttributesModelMap();
        controller.delete(saved.getId(), flash);
        assertTrue(flash.getFlashAttributes().containsKey("error"));
        assertTrue(readCommitted().hasManagedSshKeyTracking());
        assertEquals(GitTransport.HTTP, readCommitted().getTransport());
        assertFalse(flash.getFlashAttributes().toString().contains(SENTINEL));
        assertFalse(output.getAll().contains(SENTINEL));
        doNothing().when(client).deleteSshKey(42L);
        controller.delete(saved.getId(), new RedirectAttributesModelMap());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    private void makeManaged() {
        service.prepareManagedSshKeyCreation(saved.getId(), 17L, "tracked-title");
        service.configureGeneratedSsh(saved.getId(), "private", "hosts", 42L, 17L, "tracked-title");
    }

    private GitIntegration readCommitted() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> repository.findById(saved.getId()).orElseThrow());
    }

    private RuntimeException upstreamFailure() {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad request", null,
                SENTINEL.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
