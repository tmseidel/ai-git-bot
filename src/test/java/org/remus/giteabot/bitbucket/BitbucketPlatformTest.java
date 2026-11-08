package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.TestPlatforms;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BitbucketPlatformTest {

    @Test
    void getBotMentionName_isTheAccountsDisplayName() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.bitbucket.org/2.0/user"))
                .andRespond(withSuccess("""
                        {"type":"user","uuid":"{bot-uuid}","account_id":"557058:bot","nickname":"ai-bot",
                         "display_name":"AI Bot"}
                        """, MediaType.APPLICATION_JSON));
        BitbucketPlatform platform = new BitbucketPlatform("https://api.bitbucket.org/2.0", "https://bitbucket.org",
                new HttpCredentials("x-bitbucket-api-token-auth", "bb_token"), null,
                new BitbucketApiClient(builder.build(), "https://bitbucket.org"), TestPlatforms.USER_TTL);

        assertEquals(Optional.of("AI Bot"), platform.getBotMentionName());
        server.verify();
    }
}
