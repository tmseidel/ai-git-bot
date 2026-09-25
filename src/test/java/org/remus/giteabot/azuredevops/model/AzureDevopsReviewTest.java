package org.remus.giteabot.azuredevops.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AzureDevopsReviewTest {

    @Test
    void mapsVoteToState() {
        assertEquals("APPROVED", reviewWithVote(10).getState());
        assertEquals("APPROVED_WITH_SUGGESTIONS", reviewWithVote(5).getState());
        assertEquals("NO_VOTE", reviewWithVote(0).getState());
        assertEquals("WAITING_FOR_AUTHOR", reviewWithVote(-5).getState());
        assertEquals("REJECTED", reviewWithVote(-10).getState());
    }

    @Test
    void unknownOrMissingVoteIsNoVote() {
        assertEquals("NO_VOTE", reviewWithVote(null).getState());
        assertEquals("NO_VOTE", reviewWithVote(7).getState());
    }

    @Test
    void userLoginPrefersUniqueName() {
        AzureDevopsReview review = new AzureDevopsReview();
        review.setUniqueName("bot@contoso.com");
        review.setDisplayName("AI Bot");

        assertEquals("bot@contoso.com", review.getUserLogin());
    }

    @Test
    void userLoginFallsBackToDisplayName() {
        AzureDevopsReview review = new AzureDevopsReview();
        review.setDisplayName("AI Bot");

        assertEquals("AI Bot", review.getUserLogin());
    }

    @Test
    void unsupportedFieldsAreNull() {
        AzureDevopsReview review = new AzureDevopsReview();

        assertNull(review.getSubmittedAt());
        assertNull(review.getCommentsCount());
    }

    @Test
    void reviewCommentExposesThreadContext() {
        AzureDevopsReviewComment comment = new AzureDevopsReviewComment();
        comment.setId(7L);
        comment.setBody("please rename");
        comment.setPath("/src/Foo.java");
        comment.setLine(12);
        comment.setUserLogin("reviewer@contoso.com");

        assertEquals(7L, comment.getId());
        assertEquals("please rename", comment.getBody());
        assertEquals("/src/Foo.java", comment.getPath());
        assertEquals(12, comment.getLine());
        assertEquals("reviewer@contoso.com", comment.getUserLogin());
        assertNull(comment.getDiffHunk(), "Azure DevOps sends no diff context with threads");
    }

    private static AzureDevopsReview reviewWithVote(Integer vote) {
        AzureDevopsReview review = new AzureDevopsReview();
        review.setVote(vote);
        return review;
    }
}
