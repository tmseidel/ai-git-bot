package org.remus.giteabot.repository.model;

/**
 * Provider-agnostic entry of a recursive repository tree listing.
 *
 * @param path path relative to the repository root
 * @param type what kind of entry this is
 */
public record RepositoryTreeEntry(String path, Type type) {

    public enum Type {
        FILE,
        DIRECTORY,
        /** Anything else, e.g. a submodule reference. */
        OTHER;

        /**
         * Maps a git object type as reported by git tree APIs (GitHub, Gitea, GitLab):
         * {@code blob} is a file, {@code tree} a directory, anything else
         * (e.g. {@code commit} for a submodule, or {@code null}) is {@link #OTHER}.
         */
        public static Type fromGitObjectType(String gitObjectType) {
            if ("blob".equals(gitObjectType)) {
                return FILE;
            }
            if ("tree".equals(gitObjectType)) {
                return DIRECTORY;
            }
            return OTHER;
        }
    }

    public boolean isFile() {
        return type == Type.FILE;
    }
}
