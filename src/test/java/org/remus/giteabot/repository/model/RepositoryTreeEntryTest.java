package org.remus.giteabot.repository.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTreeEntryTest {

    @Test
    void fromGitObjectType_mapsBlobToFileAndTreeToDirectory() {
        assertThat(RepositoryTreeEntry.Type.fromGitObjectType("blob")).isEqualTo(RepositoryTreeEntry.Type.FILE);
        assertThat(RepositoryTreeEntry.Type.fromGitObjectType("tree")).isEqualTo(RepositoryTreeEntry.Type.DIRECTORY);
    }

    @Test
    void fromGitObjectType_submoduleUnknownOrMissingType_isOther() {
        assertThat(RepositoryTreeEntry.Type.fromGitObjectType("commit")).isEqualTo(RepositoryTreeEntry.Type.OTHER);
        assertThat(RepositoryTreeEntry.Type.fromGitObjectType("BLOB")).isEqualTo(RepositoryTreeEntry.Type.OTHER);
        assertThat(RepositoryTreeEntry.Type.fromGitObjectType(null)).isEqualTo(RepositoryTreeEntry.Type.OTHER);
    }
}
