package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AzureDevopsAddressTest {

    @Test
    void parse_splitsProjectAndRepository() {
        AzureDevopsAddress addr = AzureDevopsAddress.parse("contoso", "MyProject/my-service");

        assertEquals("contoso", addr.organization());
        assertEquals("MyProject", addr.project());
        assertEquals("my-service", addr.name());
    }

    @Test
    void parse_splitsOnFirstSlashOnly() {
        // Azure DevOps repository names may not contain '/', so anything after the
        // first separator belongs to the repository name verbatim.
        AzureDevopsAddress addr = AzureDevopsAddress.parse("contoso", "Team Project/odd/name");

        assertEquals("Team Project", addr.project());
        assertEquals("odd/name", addr.name());
    }

    @Test
    void parse_rejectsRepoWithoutSlash() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse("contoso", "my-service"));

        assertTrue(ex.getMessage().contains("Project/Repository"),
                "message should name the expected form, was: " + ex.getMessage());
    }

    @Test
    void parse_rejectsBlankSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse("contoso", "/my-service"));
        assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse("contoso", "MyProject/"));
        assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse("", "MyProject/my-service"));
    }

    @Test
    void parse_rejectsNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse(null, "MyProject/my-service"));
        assertThrows(IllegalArgumentException.class,
                () -> AzureDevopsAddress.parse("contoso", null));
    }
}
