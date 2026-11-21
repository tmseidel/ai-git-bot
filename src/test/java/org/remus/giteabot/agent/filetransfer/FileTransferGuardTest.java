package org.remus.giteabot.agent.filetransfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTransferGuardTest {

    private static final List<String> HOSTS = List.of("files.example.com", "*.cdn.example.net");

    private final FileTransferGuard guard = new FileTransferGuard(false, InetAddress::getAllByName);

    @Test
    void emptyAllowlistRefusesAndNamesTheProperty() {
        assertThatThrownBy(() -> guard.checkUrl("https://files.example.com/a.png", List.of(), "agent.x.allowed-hosts"))
                .hasMessageContaining("agent.x.allowed-hosts");
    }

    @Test
    void onlyHttpsIsAllowed() {
        assertThatThrownBy(() -> guard.checkUrl("http://files.example.com/a.png", HOSTS, "p"))
                .hasMessageContaining("Only https");
        assertThatThrownBy(() -> guard.checkUrl("file:///etc/passwd", HOSTS, "p"))
                .hasMessageContaining("Only https");
    }

    @Test
    void hostMustBeAllowed() {
        assertThat(guard.checkUrl("https://files.example.com/a.png", HOSTS, "p").getHost())
                .isEqualTo("files.example.com");
        assertThat(guard.checkUrl("https://eu.cdn.example.net/a.png", HOSTS, "p").getHost())
                .isEqualTo("eu.cdn.example.net");
        assertThatThrownBy(() -> guard.checkUrl("https://evil.example.org/a.png", HOSTS, "p"))
                .hasMessageContaining("not in p");
        assertThatThrownBy(() -> guard.checkUrl("https://files.example.com.evil.org/a.png", HOSTS, "p"))
                .hasMessageContaining("not in p");
        assertThatThrownBy(() -> guard.checkUrl("https://cdn.example.net/a.png", HOSTS, "p"))
                .hasMessageContaining("not in p");
    }

    @Test
    void userInfoIsRefused() {
        assertThatThrownBy(() -> guard.checkUrl("https://user:pw@files.example.com/a.png", HOSTS, "p"))
                .hasMessageContaining("user info");
    }

    @Test
    void refusalNeverEchoesTheQueryString() {
        assertThatThrownBy(() -> guard.checkUrl("http://files.example.com/a.png?X-Signature=secret", HOSTS, "p"))
                .message().doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fd00:ec2::254", "fe80::1", "::ffff:127.0.0.1",
            "::ffff:169.254.169.254"})
    void internalAddressesAreRefused(String address) throws Exception {
        assertThat(FileTransferGuard.isInternalAddress(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700:4700::1111"})
    void publicAddressesAreAllowed(String address) throws Exception {
        assertThat(FileTransferGuard.isInternalAddress(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void resolveRefusesAHostThatResolvesToAnInternalAddress() {
        FileTransferGuard rebinding = new FileTransferGuard(false,
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")});

        assertThatThrownBy(() -> rebinding.resolve("files.example.com"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("10.0.0.5");
    }

    @Test
    void resolveAllowsInternalAddressesOnlyWhenConfigured() throws Exception {
        FileTransferGuard permissive = new FileTransferGuard(true,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        assertThat(permissive.resolve("localhost")).hasSize(1);
    }

    @Test
    void pathMatchesGlobs() {
        assertThat(FileTransferGuard.pathMatches("assets/request-1/01.png", List.of("assets/**"))).isTrue();
        assertThat(FileTransferGuard.pathMatches("src/Main.java", List.of("assets/**"))).isFalse();
        assertThat(FileTransferGuard.pathMatches("anything/at/all.bin", List.of("**"))).isTrue();
        assertThat(FileTransferGuard.pathMatches("inputs/ref.jpg", List.of())).isFalse();
    }
}
