package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCategoriesTest {

    @Test
    void spellingsAndAliasesOfOneCategoryCountAsOne() {
        assertThat(FindingCategories.normalize("Auth")).isEqualTo("security");
        assertThat(FindingCategories.normalize(" authorization ")).isEqualTo("security");
        assertThat(FindingCategories.normalize("Error Handling")).isEqualTo("error-handling");
        assertThat(FindingCategories.normalize("error_handling")).isEqualTo("error-handling");
        assertThat(FindingCategories.normalize("Tests")).isEqualTo("testing");
        assertThat(FindingCategories.normalize("Concurrency")).isEqualTo("concurrency");
    }

    @Test
    void aMissingCategoryIsUncategorized() {
        assertThat(FindingCategories.normalize(null)).isEqualTo(FindingCategories.UNCATEGORIZED);
        assertThat(FindingCategories.normalize("  ")).isEqualTo(FindingCategories.UNCATEGORIZED);
        assertThat(FindingCategories.normalize("!!!")).isEqualTo(FindingCategories.UNCATEGORIZED);
    }
}
