package org.remus.giteabot.agent.packagemanager;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YarnCommandBuilderTest {

    @Test
    void aManifestPinIsLeftToCorepackSoItsHashIsVerified() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(manifestPinned("4.1.1"), List.of("install", "--immutable"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.preparation()).isEmpty();
        // No explicit @version: Corepack reads package.json and checks its +sha… hash.
        assertThat(invocation.command()).containsExactly("corepack", "yarn", "install", "--immutable");
        assertThat(invocation.environment())
                .containsEntry("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
                .containsEntry("COREPACK_ENABLE_AUTO_PIN", "0");
    }

    @Test
    void aClassicLockfilePassesTheMajorExplicitly() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(classicLockfile(), List.of("install"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.command()).containsExactly("corepack", "yarn@1", "install");
    }

    @Test
    void anUnpinnedYarnRepositoryIsRefused() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(unpinned(), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure())
                .contains("no Yarn version is pinned", "packageManager", "v1 format", "yarnPath");
    }

    @Test
    void anUnmarkedWorkspaceIsRefused() {
        PackageManagerDetection unknown =
                PackageManagerDetection.unknown("no package manager marker");

        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(unknown, List.of("test"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure())
                .contains("does not declare a package manager", "yarn.lock", "`npm` tool");
    }

    @Test
    void yarnIsRefusedInAnNpmRepository() {
        PackageManagerDetection npm = PackageManagerDetection.npm("package-lock.json");

        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(npm, List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure())
                .contains("managed by npm", "package-lock.json", "`npm` tool");
    }

    @Test
    void withoutCorepackTheYarnOnPathIsUsed() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("yarn"))
                .build(classicLockfile(), List.of("add", "left-pad"));

        assertThat(invocation.command()).containsExactly("yarn", "add", "left-pad");
    }

    @Test
    void aVendoredReleaseRunsWithNode() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("node"))
                .build(vendoredRelease(".yarn/releases/yarn-4.1.1.cjs"), List.of("install"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.preparation()).isEmpty();
        assertThat(invocation.command())
                .containsExactly("node", ".yarn/releases/yarn-4.1.1.cjs", "install");
    }

    @Test
    void aVendoredReleaseNeedsNode() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of("corepack"))
                .build(vendoredRelease(".yarn/releases/yarn-4.1.1.cjs"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("node", ".yarn/releases/yarn-4.1.1.cjs");
    }

    @Test
    void aMissingToolchainFailsWithoutChangingTheImage() {
        YarnCommandBuilder.Invocation invocation = builder(Set.of())
                .build(manifestPinned("4.1.1"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains(YarnCommandBuilder.YARN_INSTALL_PROPERTY);
    }

    @Test
    void yarnClassicIsInstalledThroughNpmWhenExplicitlyAllowed() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(classicLockfile(), List.of("install"));

        assertThat(invocation.failed()).isFalse();
        assertThat(invocation.preparation()).containsExactly(List.of("npm", "install", "-g", "yarn@1"));
        assertThat(invocation.command()).containsExactly("yarn", "install");
    }

    @Test
    void modernYarnIsNeverInstalledThroughNpm() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(manifestPinned("4.1.1"), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("Corepack", "only Yarn Classic");
    }

    @Test
    void anUnpinnedYarnIsRefusedInsteadOfInstalledThroughNpm() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of("npm"))
                .build(unpinned(), List.of("install"));

        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.failure()).contains("no Yarn version is pinned");
        assertThat(invocation.preparation()).isEmpty();
    }

    @Test
    void npmIsRequiredToPrepareYarnClassic() {
        YarnCommandBuilder.Invocation invocation = builder(allowingYarnInstall(), Set.of())
                .build(classicLockfile(), List.of("install"));

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

    private static PackageManagerDetection manifestPinned(String version) {
        return PackageManagerDetection.yarnFromManifest(version,
                "package.json packageManager field (yarn@" + version + ")");
    }

    private static PackageManagerDetection classicLockfile() {
        return PackageManagerDetection.yarnFromLockfile("1", null, "yarn.lock");
    }

    private static PackageManagerDetection unpinned() {
        return PackageManagerDetection.yarnFromLockfile(null, null, "yarn.lock");
    }

    private static PackageManagerDetection vendoredRelease(String yarnPath) {
        return PackageManagerDetection.yarnFromLockfile(null, yarnPath, "yarn.lock");
    }
}
