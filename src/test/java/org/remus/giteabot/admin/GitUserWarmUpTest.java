package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryPlatform;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitUserWarmUpTest {

    @Mock private GitIntegrationService gitIntegrationService;
    @Mock private RepositoryPlatformFactory repositoryPlatformFactory;
    @InjectMocks private GitUserWarmUp warmUp;

    @Test
    void resolvesEveryIntegrationEvenAfterOneFails() {
        GitIntegration broken = new GitIntegration();
        broken.setId(1L);
        GitIntegration working = new GitIntegration();
        working.setId(2L);
        RepositoryPlatform brokenPlatform = mock(RepositoryPlatform.class);
        RepositoryPlatform workingPlatform = mock(RepositoryPlatform.class);
        when(gitIntegrationService.findAll()).thenReturn(List.of(broken, working));
        when(repositoryPlatformFactory.getPlatform(broken)).thenReturn(brokenPlatform);
        when(repositoryPlatformFactory.getPlatform(working)).thenReturn(workingPlatform);
        when(brokenPlatform.getUser(null)).thenThrow(new IllegalStateException("401 Unauthorized"));

        warmUp.warmUp();

        verify(workingPlatform).getUser(null);
    }
}
