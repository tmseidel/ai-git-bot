package org.remus.giteabot.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigPropertiesTest {

    @Test
    void sandboxResourceLimitsMustBePositive() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setMemoryMb(0);
        config.getSandbox().setCpus(0);
        config.getSandbox().setPidsLimit(0);
        config.getSandbox().setWorkspaceMb(0);

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(config))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("sandbox.memoryMb", "sandbox.cpus", "sandbox.pidsLimit", "sandbox.workspaceMb");
    }
}
