package org.remus.giteabot.agent.packagemanager;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YarnCommandBuilderTest {

    @Test
    void corepackActivatesThePinnedVersion() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(yarn("4.1.1"), List.of("install", "--immutable"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.preparation()).isEmpty();
        assertThat(invocation.command())
                .containsExactly("corepack", "yarn@4.1.1", "install", "--immutable");
        assertThat(invocation.environment())
                .containsEntry("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
                .containsEntry("COREPACK_ENABLE_AUTO_PIN", "0");
    }

    @Test
    void anUnpinnedYarnRepositoryIsRefused() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(yarn(null), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure())
                .contains("no Yarn version is pinned", "packageManager", "v1 format");
    }

    @Test
    void anUnmarkedProjectStillLetsCorepackChoose() {
        PackageManagerDetection unknown =
                PackageManagerDetection.unknown("no package manager marker");

        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(unknown, List.of("test"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.command()).containsExactly("corepack", "yarn", "test");
    }

    @Test
    void withoutCorepackTheYarnOnPathIsUsed() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("yarn"))
                .build(yarn("1"), List.of("add", "left-pad"));

        assertThat(invocation.command()).containsExactly("yarn", "add", "left-pad");
    }

    @Test
    void aMissingToolchainFailsWithoutChangingTheImage() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of())
                .build(yarn("4.1.1"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains(YarnCommandBuilder.YARN_INSTALL_PROPERTY);
    }

    @Test
    void yarnClassicIsInstalledThroughNpmWhenExplicitlyAllowed() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(yarn("1"), List.of("install"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.preparation()).containsExactly(List.of("npm", "install", "-g", "yarn@1"));
        assertThat(invocation.command()).containsExactly("yarn", "install");
    }

    @Test
    void modernYarnIsNeverInstalledThroughNpm() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(yarn("4.1.1"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("Corepack", "only Yarn Classic");
    }

    @Test
    void anUnpinnedYarnIsRefusedInsteadOfInstalledThroughNpm() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(yarn(null), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("no Yarn version is pinned");
        assertThat(invocation.preparation()).isEmpty();
    }

    @Test
    void npmIsRequiredToPrepareYarnClassic() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of())
                .build(yarn("1"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("npm");
    }

    private YarnCommandBuilder builder(Set<String> available) {
        return builder(new AgentConfigProperties(), available);
    }

    private YarnCommandBuilder builder(AgentConfigProperties config, Set<String> available) {
        return new YarnCommandBuilder(config, available::contains);
    }

    private static AgentConfigProperties allowingYarnInstall() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getValidation().setAllowYarnInstall(true);
        return config;
    }

    private static PackageManagerDetection yarn(String versionSpec) {
        return new PackageManagerDetection(PackageManager.YARN, versionSpec, "yarn.lock");
    }
}
