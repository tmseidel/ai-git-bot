package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.azuredevops.AzureDevopsApiClient;
import org.remus.giteabot.azuredevops.AzureDevopsPlatform;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.TestPlatforms;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class ReviewLinkStylesTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final ReviewLinkStyle.FileRef REF =
            new ReviewLinkStyle.FileRef("src/Foo.java", null, 10, 12);

    @Test
    void githubLinkIsDerivedFromTheCloneUrl() {
        ReviewLinkStyle style = ReviewLinkStyles.forProvider(RepositoryType.GITHUB,
                TestPlatforms.of(mock(RepositoryApiClient.class)), "acme", "app", SHA);

        assertEquals(TestPlatforms.URL + "/acme/app/blob/" + SHA + "/src/Foo.java#L10-L12", style.fileUrl(REF));
    }

    @Test
    void azureLinkComesFromThePlatform() {
        AzureDevopsPlatform platform = new AzureDevopsPlatform("https://dev.azure.com", "https://dev.azure.com",
                new HttpCredentials(null, "pat"), null,
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null),
                TestPlatforms.USER_TTL);

        ReviewLinkStyle style = ReviewLinkStyles.forProvider(RepositoryType.AZURE_DEVOPS,
                platform, "contoso", "MyProject/my-service", SHA);

        assertEquals("https://dev.azure.com/contoso/MyProject/_git/my-service?path=/src/Foo.java&version=GC" + SHA
                        + "&line=10&lineEnd=13&lineStartColumn=1&lineEndColumn=1&lineStyle=plain&_a=contents",
                style.fileUrl(REF));
    }

    @Test
    void azureLinkIsPlainTextWhenTheRepositoryCannotBeAddressed() {
        AzureDevopsPlatform platform = new AzureDevopsPlatform("https://dev.azure.com", "https://dev.azure.com",
                new HttpCredentials(null, "pat"), null,
                new AzureDevopsApiClient(RestClient.builder().build(), "https://dev.azure.com", null),
                TestPlatforms.USER_TTL);

        assertNull(ReviewLinkStyles.forProvider(RepositoryType.AZURE_DEVOPS,
                platform, "contoso", "my-service", SHA).fileUrl(REF));
    }

    @Test
    void unknownRevisionOrPlatformDisablesLinks() {
        assertSame(ReviewLinkStyle.NONE, ReviewLinkStyles.forProvider(RepositoryType.GITHUB,
                TestPlatforms.of(mock(RepositoryApiClient.class)), "acme", "app", null));
        assertSame(ReviewLinkStyle.NONE, ReviewLinkStyles.forProvider(RepositoryType.GITHUB,
                null, "acme", "app", SHA));
    }
}
