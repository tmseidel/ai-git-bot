package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitUserWarmUpTest {

    @Mock private GitIntegrationService gitIntegrationService;
    @Mock private GitUserService gitUserService;
    @InjectMocks private GitUserWarmUp warmUp;

    @Test
    void resolvesEveryIntegrationEvenAfterOneFails() {
        GitIntegration broken = new GitIntegration();
        broken.setId(1L);
        GitIntegration working = new GitIntegration();
        working.setId(2L);
        when(gitIntegrationService.findAll()).thenReturn(List.of(broken, working));
        when(gitUserService.getGitUser(broken)).thenThrow(new IllegalStateException("401 Unauthorized"));

        warmUp.warmUp();

        verify(gitUserService).getGitUser(working);
    }
}
